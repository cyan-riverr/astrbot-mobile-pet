"""Mobile GIF pet bridge for AstrBot.

The Android pet connects over WebSocket. Touch interactions are converted into
synthetic AstrBot message events and committed to AstrBot's normal pipeline.
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import re
import time
import uuid
from typing import Any
from urllib.parse import parse_qs, urlparse
import shlex

import websockets
from websockets.server import WebSocketServerProtocol

from astrbot.api.event import AstrMessageEvent, filter
from astrbot.api.message_components import Image, Plain
from astrbot.api.platform import AstrBotMessage, MessageMember, MessageType
from astrbot.api.provider import LLMResponse, ProviderRequest
from astrbot.api.star import Context, Star, register

logger = logging.getLogger("astrbot_plugin_mobile_pet")

VALID_STATES = {"idle", "working", "touch", "hug", "feed", "bath", "sleep", "drag", "walk"}


def load_config(plugin_dir: str) -> dict[str, Any]:
    conf_path = os.path.join(plugin_dir, "_conf.json")
    default: dict[str, Any] = {
        "ws_host": "0.0.0.0",
        "ws_port": 1016,
        "token": "my_secret_token",
        "target_unified_msg_origin": "",
        "sender_nickname": "user",
        "enable_prompt_injection": True,
        "pet_prompt": (
            "你可以控制已连接的手机桌宠。标签会由插件执行并从最终回复中移除；仅在确实想让桌宠表现动作或显示气泡时自然使用。"
            "可用状态：idle、working、touch、hug、feed、bath、sleep、drag、walk；说话只用[pet:bubble:文字]，不要把speak当素材状态。"
            "用法组合："
            "①单独说话：[pet:bubble:文字]；"
            "②单独切换状态：[pet:状态]；"
            "③边切状态边说：[pet:bubble:文字 state=状态]；"
            "④单独走路：[pet:walk dx=80 dy=0 duration=5000]；"
            "⑤边走边说：[pet:walk dx=80 dy=0 duration=5000 text=要说的话]；"
            "⑥请求她分享当前手机屏幕：[pet:request action=screen_share text=想看你的屏幕]。"
            "walk参数：dx左右位移、dy上下位移、duration移动时长毫秒，walk时状态自动切换为walk动画无需额外指定state。"
        ),
    }

    if os.path.exists(conf_path):
        try:
            with open(conf_path, "r", encoding="utf-8") as f:
                user_conf = json.load(f)
            if isinstance(user_conf, dict):
                default.update(user_conf)
        except Exception as exc:  # noqa: BLE001
            logger.error("[mobile_pet] failed to load config: %s", exc)
    else:
        try:
            with open(conf_path, "w", encoding="utf-8") as f:
                json.dump(default, f, ensure_ascii=False, indent=4)
            logger.info("[mobile_pet] created default config: %s", conf_path)
        except Exception as exc:  # noqa: BLE001
            logger.warning("[mobile_pet] failed to create default config: %s", exc)

    return default


def parse_umo(umo: str) -> tuple[str, str, str]:
    parts = str(umo).split(":", 2)
    if len(parts) != 3:
        raise ValueError(f"invalid target_unified_msg_origin: {umo!r}")
    return parts[0], parts[1], parts[2]


def is_mobile_pet_inject_event(event: AstrMessageEvent) -> bool:
    try:
        if event.get_extra("_mobile_pet_inject"):
            return True
    except Exception:
        pass

    try:
        raw = getattr(getattr(event, "message_obj", None), "raw_message", None)
        if isinstance(raw, dict) and raw.get("_mobile_pet_inject"):
            return True
    except Exception:
        pass

    return False


class PetWebSocketServer:
    def __init__(self, config: dict[str, Any], star_context: Context) -> None:
        self.config = config
        self.star_context = star_context
        self.clients: set[WebSocketServerProtocol] = set()
        self.server: Any | None = None

    async def start(self) -> None:
        host = str(self.config.get("ws_host") or "0.0.0.0")
        port = int(self.config.get("ws_port") or 1016)
        self.server = await websockets.serve(
            self.handler,
            host,
            port,
            ping_interval=30,
            ping_timeout=10,
        )
        logger.info("[mobile_pet] WebSocket listening on ws://%s:%s/pet", host, port)

    async def stop(self) -> None:
        if self.server:
            self.server.close()
            await self.server.wait_closed()
            self.server = None
        for client in list(self.clients):
            try:
                await client.close()
            except Exception:
                pass
        self.clients.clear()

    async def handler(self, websocket: WebSocketServerProtocol, path: str = "") -> None:
        request = getattr(websocket, "request", None)
        ws_path = (
            path
            or getattr(websocket, "path", "")
            or getattr(request, "path", "")
        )
        params = parse_qs(urlparse(ws_path).query)
        client_token = params.get("token", [None])[0]
        expected_token = str(self.config.get("token") or "")

        if expected_token and client_token != expected_token:
            logger.warning("[mobile_pet] rejected client with invalid token")
            await websocket.close(4001, "Invalid token")
            return

        self.clients.add(websocket)
        logger.info("[mobile_pet] client connected, total=%d", len(self.clients))
        try:
            await self.send_to(
                websocket,
                {
                    "type": "state",
                    "state": "idle",
                    "bubble": "连接成功啦~",
                    "duration": 3000,
                },
            )
            async for raw_message in websocket:
                await self.on_message(websocket, raw_message)
        except websockets.exceptions.ConnectionClosed:
            pass
        finally:
            self.clients.discard(websocket)
            logger.info("[mobile_pet] client disconnected, total=%d", len(self.clients))

    async def on_message(self, websocket: WebSocketServerProtocol, raw: str) -> None:
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            logger.warning("[mobile_pet] invalid json from client: %s", raw)
            return

        msg_type = str(data.get("type") or "")
        msg_id = str(data.get("msg_id") or "")

        if msg_type == "ping":
            await self.send_to(websocket, {"type": "pong", "timestamp": data.get("timestamp")})
            return

        if msg_type == "hello":
            await self.send_to(websocket, {"type": "connection", "status": "authenticated"})
            return

        if msg_type == "interaction":
            action = str(data.get("action") or "unknown")
            await self.handle_interaction(websocket, action)
            if msg_id:
                await self.send_to(websocket, {"type": "ack", "msg_id": msg_id, "status": "ok"})
            return

        if msg_type == "chat":
            text = str(data.get("text") or "").strip()
            if text:
                ok = await self.inject_mobile_pet_message(f"[from-pet] {text}", action="chat")
                await self.send_to(websocket, {"type": "ack", "msg_id": msg_id, "status": "ok" if ok else "error"})
            return

        if msg_type == "screen_snapshot":
            ok = await self.handle_screen_snapshot(data)
            if msg_id:
                await self.send_to(websocket, {"type": "ack", "msg_id": msg_id, "status": "ok" if ok else "error"})
            return
        if msg_type == "request_response":
            action = str(data.get("action") or "response")
            status = str(data.get("status") or "accepted")
            if action == "screen_share" and status == "accepted":
                if msg_id:
                    await self.send_to(websocket, {"type": "ack", "msg_id": msg_id, "status": "ok"})
                return
            action_text = {
                "pat": "摸摸请求",
                "feed": "投喂请求",
                "hug": "抱抱请求",
                "poke": "戳戳请求",
                "call": "叫你请求",
                "screen_share": "屏幕共享请求",
                "screen_snapshot": "屏幕共享请求",
            }.get(action, f"{action} 请求")
            sender_name = str(self.config.get("sender_nickname") or "user")
            verb = "拒绝了" if status == "rejected" else "回应了"
            ok = await self.inject_mobile_pet_message(
                f"[pet-response] {sender_name}{verb}你的{action_text}",
                action="request_response",
            )
            if msg_id:
                await self.send_to(websocket, {"type": "ack", "msg_id": msg_id, "status": "ok" if ok else "error"})
            return

        logger.debug("[mobile_pet] ignored client message: %s", data)

    async def handle_interaction(self, websocket: WebSocketServerProtocol, action: str) -> None:
        sender_name = str(self.config.get("sender_nickname") or "user")
        action_map = {
            "poke": {
                "text": f"[pet-touch] {sender_name}戳了你一下",
                "state": "touch",
                "bubble": "被戳了，好痒~",
            },
            "hug": {
                "text": f"[pet-touch] {sender_name}抱了你一下",
                "state": "hug",
                "bubble": "好温暖呀~",
            },
            "feed": {
                "text": f"[pet-touch] {sender_name}喂了你一次",
                "state": "feed",
                "bubble": "好好吃",
            },
            "pat": {
                "text": f"[pet-touch] {sender_name}摸了摸你",
                "state": "touch",
                "bubble": "被摸摸了",
            },
            "call": {
                "text": f"[pet-touch] {sender_name}叫了你一声",
                "state": "speak",
                "bubble": "我在",
            },
        }
        info = action_map.get(
            action,
            {
                "text": f"[pet-touch] {sender_name}和桌宠互动了一下: {action}",
                "state": "idle",
                "bubble": "嗯？",
            },
        )

        await self.send_to(
            websocket,
            {
                "type": "state",
                "state": info["state"],
                "duration": 3000,
            },
        )
        ok = await self.inject_mobile_pet_message(str(info["text"]), action=action)
        if not ok:
            logger.warning("[mobile_pet] failed to inject interaction: %s", action)

    async def inject_mobile_pet_message(self, text: str, action: str = "") -> bool:
        umo = str(self.config.get("target_unified_msg_origin") or "").strip()
        if not umo:
            logger.warning("[mobile_pet] target_unified_msg_origin is not configured")
            return False

        try:
            platform_id, message_type_str, session_id = parse_umo(umo)
        except ValueError as exc:
            logger.error("[mobile_pet] %s", exc)
            return False


        platform = self.star_context.get_platform_inst(platform_id)
        if platform is None:
            logger.warning("[mobile_pet] platform not found: %s", platform_id)
            return False

        timestamp = int(time.time())
        message = AstrBotMessage()
        message.message_str = text
        message.message = [Plain(text)]
        message.type = MessageType.FRIEND_MESSAGE
        message.self_id = str(getattr(platform, "client_self_id", "") or "")
        message.session_id = session_id
        message.sender = MessageMember(
            user_id=session_id,
            nickname=str(self.config.get("sender_nickname") or "user"),
        )
        message.message_id = f"mobile_pet_{int(time.time() * 1000)}_{uuid.uuid4().hex[:8]}"
        message.timestamp = timestamp
        message.raw_message = {
            "_mobile_pet_inject": True,
            "source": "mobile_pet",
            "action": action,
            "message_type": message_type_str,
            "raw_message": text,
            "user_id": session_id,
            "time": timestamp,
        }

        try:
            event = platform.create_event(message)
            event.is_wake = True
            event.is_at_or_wake_command = True
            event.set_extra("source", "mobile_pet")
            event.set_extra("_mobile_pet_inject", True)
            event.set_extra("mobile_pet_action", action)
            platform.commit_event(event)
        except Exception as exc:  # noqa: BLE001
            logger.error("[mobile_pet] inject failed: %s", exc, exc_info=True)
            return False

        logger.info(
            "[mobile_pet] injected pipeline event: action=%s umo=%s text=%s",
            action,
            umo,
            text,
        )
        return True

    async def handle_screen_snapshot(self, data: dict[str, Any]) -> bool:
        image_base64 = str(data.get("image_base64") or data.get("image") or "")
        if not image_base64:
            logger.warning("[mobile_pet] screen_snapshot missing image_base64")
            return False

        plugin_dir = os.path.dirname(os.path.abspath(__file__))
        temp_dir = os.path.join(plugin_dir, "pet_screens")
        os.makedirs(temp_dir, exist_ok=True)
        image_path = os.path.join(temp_dir, f"screen_{int(time.time() * 1000)}_{uuid.uuid4().hex[:8]}.jpg")
        try:
            with open(image_path, "wb") as f:
                f.write(base64.b64decode(image_base64))
        except Exception as exc:  # noqa: BLE001
            logger.error("[mobile_pet] save screen snapshot failed: %s", exc, exc_info=True)
            return False

        sender_name = str(self.config.get("sender_nickname") or "对方")
        caption = str(data.get("caption") or f"[pet-screen] {sender_name}给你看了当前手机屏幕")
        return await self.inject_mobile_pet_image_message(caption, image_path, action="screen_snapshot")

    async def inject_mobile_pet_image_message(self, text: str, image_path: str, action: str = "") -> bool:
        umo = str(self.config.get("target_unified_msg_origin") or "").strip()
        if not umo:
            logger.warning("[mobile_pet] target_unified_msg_origin is not configured")
            return False

        try:
            platform_id, message_type_str, session_id = parse_umo(umo)
        except ValueError as exc:
            logger.error("[mobile_pet] %s", exc)
            return False


        platform = self.star_context.get_platform_inst(platform_id)
        if platform is None:
            logger.warning("[mobile_pet] platform not found: %s", platform_id)
            return False

        timestamp = int(time.time())
        message = AstrBotMessage()
        message.message_str = text
        message.message = [Plain(text), Image(file=image_path)]
        message.type = MessageType.FRIEND_MESSAGE
        message.self_id = str(getattr(platform, "client_self_id", "") or "")
        message.session_id = session_id
        message.sender = MessageMember(
            user_id=session_id,
            nickname=str(self.config.get("sender_nickname") or "user"),
        )
        message.message_id = f"mobile_pet_screen_{int(time.time() * 1000)}_{uuid.uuid4().hex[:8]}"
        message.timestamp = timestamp
        message.raw_message = {
            "_mobile_pet_inject": True,
            "source": "mobile_pet",
            "action": action,
            "message_type": message_type_str,
            "raw_message": text,
            "image_path": image_path,
            "user_id": session_id,
            "time": timestamp,
        }

        try:
            event = platform.create_event(message)
            event.is_wake = True
            event.is_at_or_wake_command = True
            event.set_extra("source", "mobile_pet")
            event.set_extra("_mobile_pet_inject", True)
            event.set_extra("mobile_pet_action", action)
            platform.commit_event(event)
        except Exception as exc:  # noqa: BLE001
            logger.error("[mobile_pet] image inject failed: %s", exc, exc_info=True)
            return False

        logger.info("[mobile_pet] injected screen snapshot: umo=%s path=%s", umo, image_path)
        return True

    async def send_to(self, websocket: WebSocketServerProtocol, data: dict[str, Any]) -> None:
        try:
            await websocket.send(json.dumps(data, ensure_ascii=False))
        except Exception as exc:  # noqa: BLE001
            logger.warning("[mobile_pet] send failed: %s", exc)

    async def broadcast(self, data: dict[str, Any]) -> int:
        message = json.dumps(data, ensure_ascii=False)
        logger.info("[mobile_pet] broadcasting to pet: %s", message)
        disconnected: set[WebSocketServerProtocol] = set()
        sent = 0
        for websocket in list(self.clients):
            try:
                await websocket.send(message)
                sent += 1
            except Exception:
                disconnected.add(websocket)
        self.clients -= disconnected
        return sent

    async def push_request(self, action: str, text: str, ttl_ms: int = 30000) -> tuple[int, str]:
        request_id = f"pet_req_{int(time.time() * 1000)}_{uuid.uuid4().hex[:8]}"
        payload = {
            "type": "pet_request",
            "request_id": request_id,
            "action": action,
            "state": self.state_for_action(action),
            "text": text,
            "bubble": text,
            "duration": ttl_ms,
            "ttl_ms": ttl_ms,
        }
        count = await self.broadcast(payload)
        return count, request_id

    @staticmethod
    def state_for_action(action: str) -> str:
        if action == "hug":
            return "hug"
        if action == "feed":
            return "feed"
        if action in {"pat", "poke"}:
            return "touch"
        if action == "call":
            return "speak"
        return "speak"


@register("astrbot_plugin_mobile_pet", "cyanriver", "手机桌宠 WebSocket 插件", "1.1.0")
class MobilePetPlugin(Star):
    PET_ONLY_TAG = "[pet_only]"
    PET_ONLY_ACTIONS = {"chat", "screen_snapshot", "request_response"}
    PET_TAG_RE = re.compile(r"\[pet:(say|bubble|request|walk)(?::|\s+)?([^\]]*)\]")
    PET_SIMPLE_TAG_RE = re.compile(r"\[\s*(?:pet|桌宠)\s*[:：]\s*([^\]]+?)\s*\]")

    def __init__(self, context: Context, config: dict[str, Any] | None = None) -> None:
        super().__init__(context, config)
        self.config: dict[str, Any] = config or {}
        if not self.config:
            plugin_dir = os.path.dirname(os.path.abspath(__file__))
            self.config = load_config(plugin_dir)
        self.ws_server: PetWebSocketServer | None = None
        self._task: asyncio.Task | None = None

    async def initialize(self) -> None:
        self.ws_server = PetWebSocketServer(self.config, self.context)
        self._task = asyncio.create_task(self.ws_server.start())
        logger.info("[mobile_pet] plugin initialized")

    async def terminate(self) -> None:
        if self.ws_server:
            await self.ws_server.stop()
        if self._task and not self._task.done():
            self._task.cancel()

    @filter.on_llm_request(priority=1000)
    async def inject_pet_prompt(self, event: AstrMessageEvent, request: ProviderRequest) -> None:
        prompts: list[str] = []
        if self.config.get("enable_prompt_injection", True):
            configured = str(self.config.get("pet_prompt") or "").strip()
            if configured:
                prompts.append(configured)

        if is_mobile_pet_inject_event(event):
            action = str(event.get_extra("mobile_pet_action") or "")
            if action in self.PET_ONLY_ACTIONS:
                prompts.append(
                    "如果这条消息来自桌宠聊天或桌面截图共享，你的最终可见回复应该只包含要对桌宠说的话。"
                    "不要把回复写成面对QQ聊天窗口的格式，不要解释来源，不要添加额外前缀。"
                )

        if not prompts:
            return
        newline = chr(10)
        injected = (newline * 2).join(prompts)
        current = request.system_prompt or ""
        request.system_prompt = current + (newline if current else "") + injected

    @filter.on_llm_response(priority=1001)
    async def dispatch_pet_tags_from_llm(self, event: AstrMessageEvent, response: LLMResponse) -> None:
        if not self.ws_server or response is None:
            return
        text = response.completion_text or ""
        if not text or not re.search(r"\[\s*(?:pet|桌宠)\s*[:：]", text):
            return
        clean_text, payloads = self._parse_pet_control_tags(text)
        if not payloads:
            return
        sent = 0
        for payload in payloads:
            sent += await self.ws_server.broadcast(payload)
        event.set_extra("mobile_pet_tags_dispatched", True)
        response.completion_text = clean_text
        logger.info("[mobile_pet] dispatched %d pet tag(s) to %d client(s) from llm_response", len(payloads), sent)

    @filter.on_decorating_result(priority=1001)
    async def dispatch_pet_tags(self, event: AstrMessageEvent) -> None:
        result = event.get_result()
        if result is None or not getattr(result, "chain", None):
            return
        text = self._extract_plain_reply_text(result.chain)
        if not text or not re.search(r"\[\s*(?:pet|桌宠)\s*[:：]", text):
            return

        clean_text, payloads = self._parse_pet_control_tags(text)
        if clean_text != text:
            self._replace_plain_reply_text(result.chain, clean_text)
            if not clean_text:
                result.chain.clear()
                event.stop_event()

        if event.get_extra("mobile_pet_tags_dispatched"):
            logger.info("[mobile_pet] stripped %d already-dispatched pet tag(s) from decorating_result", len(payloads))
            return
        if not payloads or not self.ws_server:
            logger.info("[mobile_pet] stripped pet tag text without dispatch: payloads=%d ws=%s", len(payloads), bool(self.ws_server))
            return

        sent = 0
        for payload in payloads:
            sent += await self.ws_server.broadcast(payload)
        event.set_extra("mobile_pet_tags_dispatched", True)
        logger.info("[mobile_pet] dispatched %d pet tag(s) to %d client(s) from decorating_result", len(payloads), sent)

    @filter.on_decorating_result(priority=1000)
    async def mirror_reply_to_pet(self, event: AstrMessageEvent) -> None:
        if not self.ws_server:
            return
        if not is_mobile_pet_inject_event(event):
            return
        action = str(event.get_extra("mobile_pet_action") or "")
        if action not in self.PET_ONLY_ACTIONS:
            return
        result = event.get_result()
        if result is None or not getattr(result, "chain", None):
            return
        text = self._extract_plain_reply_text(result.chain)
        if not text:
            result.chain.clear()
            event.stop_event()
            return
        bubble_text = re.sub(r"\[NEXT:[^\]]+\]", "", text).strip()
        bubble_text = re.sub(r"\s+", " ", bubble_text)
        sent = await self.ws_server.broadcast({
            "type": "reply",
            "state": "speak",
            "text": text,
            "bubble": bubble_text,
            "duration": 5000,
        })
        result.chain.clear()
        event.stop_event()
        logger.info("[mobile_pet] mirrored pet reply to %d client(s): action=%s text=%s", sent, action, text[:120])

    def _extract_plain_reply_text(self, chain: list[Any]) -> str:
        pieces: list[str] = []
        for comp in chain:
            if isinstance(comp, Plain):
                pieces.append(comp.text)
        raw_text = "".join(pieces).strip()
        if not raw_text:
            return ""
        text = re.sub(r"<thinking>.*?</thinking>", "", raw_text, flags=re.S).strip()
        text = text.replace(self.PET_ONLY_TAG, "", 1).strip()
        return text

    def _replace_plain_reply_text(self, chain: list[Any], text: str) -> None:
        replaced = False
        for comp in list(chain):
            if isinstance(comp, Plain):
                if not replaced:
                    comp.text = text
                    replaced = True
                else:
                    comp.text = ""

    def _parse_pet_control_tags(self, text: str) -> tuple[str, list[dict[str, Any]]]:
        payloads: list[dict[str, Any]] = []

        def repl(match: re.Match[str]) -> str:
            kind = match.group(1)
            raw_args = match.group(2) or ""
            args = self._parse_pet_tag_args(raw_args)
            if kind in ("say", "bubble"):
                bubble = args.get("text") or args.get("bubble") or ""
                if bubble:
                    payloads.append({
                        "type": "reply",
                        "state": args.get("state") or "speak",
                        "text": bubble,
                        "bubble": bubble,
                        "duration": self._safe_int(args.get("duration"), 5000),
                    })
            elif kind == "request":
                action = self._normalize_request_action(args.get("action") or "poke")
                bubble = args.get("text") or args.get("bubble") or self._default_request_bubble(action)
                payloads.append({
                    "type": "pet_request",
                    "state": args.get("state") or self._state_for_action(action),
                    "action": action,
                    "request_id": str(uuid.uuid4()),
                    "message": bubble,
                    "duration": self._safe_int(args.get("duration"), 5000),
                })
            elif kind == "walk":
                walk_payload: dict[str, Any] = {
                    "type": "walk",
                    "dx": self._safe_int(args.get("dx"), 0),
                    "dy": self._safe_int(args.get("dy"), 0),
                    "duration": self._safe_int(args.get("duration"), 5000),
                    "state": args.get("state") or "walk",
                }
                walk_bubble = args.get("text") or args.get("bubble") or ""
                if walk_bubble:
                    walk_payload["bubble"] = walk_bubble
                    walk_payload["message"] = walk_bubble
                payloads.append(walk_payload)
            return ""

        def simple_repl(match: re.Match[str]) -> str:
            raw = (match.group(1) or "").strip()
            payload = self._payload_from_simple_pet_tag(raw)
            if payload:
                payloads.append(payload)
            return ""

        clean = self.PET_TAG_RE.sub(repl, text)
        clean = self.PET_SIMPLE_TAG_RE.sub(simple_repl, clean)
        clean = clean.strip()
        return clean, payloads

    def _payload_from_simple_pet_tag(self, raw: str) -> dict[str, Any]:
        if not raw:
            return {}
        parts = raw.split()
        head = parts[0].strip()
        lower = head.lower()

        if lower.startswith(("bubble:", "message:", "text:", "气泡:", "说话:")):
            bubble = head.split(":", 1)[1].strip()
            state = "speak"
            remaining: list[str] = []
            for token in parts[1:]:
                if token.startswith(("state=", "状态=")):
                    state = token.split("=", 1)[1].strip() or "speak"
                elif "=" in token:
                    continue
                else:
                    remaining.append(token)
            if remaining:
                bubble = (bubble + " " + " ".join(remaining)).strip()
            if not bubble:
                return {}
            return {
                "type": "reply",
                "state": state,
                "text": bubble,
                "bubble": bubble,
                "message": bubble,
                "duration": 5000,
            }

        if lower.startswith("walk") or lower.startswith("走"):
            values: dict[str, Any] = {"dx": 0, "dy": 0, "duration": 5000}
            walk_bubble = ""
            for token in parts[1:]:
                if "=" not in token:
                    continue
                key, value = token.split("=", 1)
                key = key.strip().lower()
                if key in ("dx", "dy", "duration"):
                    values[key] = self._safe_int(value.strip(), values[key])
                elif key in ("text", "bubble"):
                    walk_bubble = value.strip()
            result: dict[str, Any] = {"type": "walk", "state": "walk", **values}
            if walk_bubble:
                result["bubble"] = walk_bubble
                result["message"] = walk_bubble
            return result

        state_aliases = {
            "idle": "idle", "默认": "idle",
            "working": "working", "work": "working", "工作": "working",
            "touch": "touch", "开心": "touch", "摸摸": "touch", "pat": "touch",
            "hug": "hug", "抱抱": "hug",
            "sleep": "sleep", "睡觉": "sleep",
            "feed": "feed", "投喂": "feed", "喂食": "feed",
            "bath": "bath", "洗澡": "bath",
            "speak": "speak", "说话": "speak",
            "drag": "drag", "拖拽": "drag",
            "walk": "walk", "走路": "walk",
            "poke": "touch", "戳戳": "touch",
        }
        state = state_aliases.get(lower, state_aliases.get(head, ""))
        if not state:
            return {}
        return {"type": "state", "state": state, "duration": 5000}

    def _parse_pet_tag_args(self, raw_args: str) -> dict[str, str]:
        args: dict[str, str] = {}
        try:
            parts = shlex.split(raw_args)
        except ValueError:
            parts = raw_args.split()
        pending_key = "text"
        free: list[str] = []
        for part in parts:
            if "=" in part:
                key, value = part.split("=", 1)
                args[key.strip()] = value.strip()
            else:
                free.append(part)
        if free and pending_key not in args:
            args[pending_key] = " ".join(free).strip()
        return args

    def _safe_int(self, value: Any, default: int) -> int:
        try:
            return int(value)
        except Exception:
            return default

    def _normalize_request_action(self, action: str) -> str:
        return {
            "screen": "screen_share",
            "screen_snapshot": "screen_share",
            "share_screen": "screen_share",
            "screenshot": "screen_share",
        }.get(action, action)

    def _state_for_action(self, action: str) -> str:
        return {
            "poke": "touch",
            "pat": "touch",
            "hug": "hug",
            "feed": "feed",
            "call": "speak",
            "screen_share": "touch",
        }.get(action, "speak")

    def _default_request_bubble(self, action: str) -> str:
        return {
            "poke": "求戳戳",
            "pat": "求摸摸",
            "hug": "求抱抱",
            "feed": "求投喂",
            "call": "叫我一下",
            "screen_share": "想看你的屏幕",
        }.get(action, "求互动")

    @filter.command("pet_push")
    async def pet_push(self, event: AstrMessageEvent) -> None:
        if is_mobile_pet_inject_event(event):
            return

        parts = event.message_str.strip().split(None, 2)
        if len(parts) < 2:
            event.set_result(event.plain_result("用法：/pet_push <state> [bubble]"))
            return

        state = parts[1]
        bubble = parts[2] if len(parts) > 2 else ""
        if state not in VALID_STATES:
            event.set_result(
                event.plain_result(
                    f"无效状态: {state}，可用: {', '.join(sorted(VALID_STATES))}"
                )
            )
            return

        if not self.ws_server or not self.ws_server.clients:
            event.set_result(event.plain_result("当前没有桌宠客户端连接"))
            return

        count = await self.ws_server.broadcast(
            {"type": "state", "state": state, "bubble": bubble, "duration": 5000}
        )
        event.set_result(event.plain_result(f"已推送 [{state}] 给 {count} 个客户端"))

    @filter.command("pet_say")
    async def pet_say(self, event: AstrMessageEvent) -> None:
        if is_mobile_pet_inject_event(event):
            return

        parts = event.message_str.strip().split(None, 1)
        if len(parts) < 2:
            event.set_result(event.plain_result("用法：/pet_say <消息内容>"))
            return

        if not self.ws_server or not self.ws_server.clients:
            event.set_result(event.plain_result("当前没有桌宠客户端连接"))
            return

        bubble = parts[1]
        count = await self.ws_server.broadcast(
            {"type": "message", "state": "speak", "bubble": bubble, "duration": 5000}
        )
        event.set_result(event.plain_result(f"桌宠已发送给 {count} 个客户端"))


    @filter.command("pet_request")
    async def pet_request(self, event: AstrMessageEvent) -> None:
        if is_mobile_pet_inject_event(event):
            return

        parts = event.message_str.strip().split(None, 2)
        if len(parts) < 2:
            event.set_result(event.plain_result("用法：/pet_request <摸摸|投喂|抱抱|戳戳|叫我|action> [显示文字]"))
            return

        raw_action = parts[1].strip()
        action_aliases = {
            "摸摸": "pat",
            "摸": "pat",
            "pat": "pat",
            "投喂": "feed",
            "喂食": "feed",
            "喂": "feed",
            "feed": "feed",
            "抱抱": "hug",
            "抱": "hug",
            "hug": "hug",
            "戳戳": "poke",
            "戳": "poke",
            "poke": "poke",
            "叫我": "call",
            "call": "call",
        }
        action = action_aliases.get(raw_action, raw_action)
        default_text = {
            "pat": "可以摸摸我吗",
            "feed": "我饿了，可以投喂一下吗",
            "hug": "想要抱一下",
            "poke": "戳戳我，我看看疼不疼",
            "call": "叫我一声好不好",
        }.get(action, f"想让你回应一下：{raw_action}")
        text = parts[2].strip() if len(parts) > 2 and parts[2].strip() else default_text

        if not self.ws_server or not self.ws_server.clients:
            event.set_result(event.plain_result("当前没有桌宠客户端连接"))
            return

        count, request_id = await self.ws_server.push_request(action, text)
        event.set_result(event.plain_result(f"已向 {count} 个桌宠发送请求：{text} ({request_id})"))

    @filter.command("pet_status")
    async def pet_status(self, event: AstrMessageEvent) -> None:
        if is_mobile_pet_inject_event(event):
            return

        count = len(self.ws_server.clients) if self.ws_server else 0
        port = self.config.get("ws_port", "?")
        umo = self.config.get("target_unified_msg_origin") or "未配置"
        event.set_result(
            event.plain_result(
                f"桌宠状态\n连接数: {count}\n端口: {port}\n目标会话: {umo}"
            )
        )

    @filter.command("pet_direct")
    async def pet_direct(self, event: AstrMessageEvent) -> None:
        if is_mobile_pet_inject_event(event):
            return

        parts = event.message_str.strip().split(None, 1)
        bubble = parts[1].strip() if len(parts) > 1 and parts[1].strip() else "直推测试"
        if not self.ws_server:
            event.set_result(event.plain_result("桌宠服务未启动"))
            return
        count = await self.ws_server.broadcast({
            "type": "reply",
            "state": "speak",
            "text": bubble,
            "bubble": bubble,
            "message": bubble,
            "duration": 5000,
        })
        event.set_result(event.plain_result(f"桌宠直推气泡已发送给 {count} 个客户端"))

    @filter.command("pet_reload")
    async def pet_reload(self, event: AstrMessageEvent) -> None:
        if is_mobile_pet_inject_event(event):
            return

        plugin_dir = os.path.dirname(os.path.abspath(__file__))
        self.config = load_config(plugin_dir)
        if self.ws_server:
            self.ws_server.config = self.config
        event.set_result(event.plain_result("桌宠配置已重载"))
