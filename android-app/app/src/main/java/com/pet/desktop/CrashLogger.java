package com.pet.desktop;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CrashLogger {
    private static final String FILE_NAME = "last_crash.txt";
    private static Thread.UncaughtExceptionHandler previousHandler;

    private CrashLogger() {}

    public static void install(Context context) {
        Context appContext = context.getApplicationContext();
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            writeCrash(appContext, thread, throwable);
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    public static String readLastCrash(Context context) {
        try {
            File file = new File(context.getFilesDir(), FILE_NAME);
            if (!file.exists()) return "";
            byte[] data = new byte[(int) file.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            int read = in.read(data);
            in.close();
            if (read <= 0) return "";
            return new String(data, 0, read, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    public static void clearLastCrash(Context context) {
        try {
            new File(context.getFilesDir(), FILE_NAME).delete();
        } catch (Exception ignored) {}
    }

    private static void writeCrash(Context context, Thread thread, Throwable throwable) {
        try {
            StringWriter stack = new StringWriter();
            throwable.printStackTrace(new PrintWriter(stack));
            String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            String text = "time=" + now + "\n"
                    + "thread=" + thread.getName() + "\n"
                    + "sdk=" + Build.VERSION.SDK_INT + "\n"
                    + "brand=" + Build.BRAND + "\n"
                    + "model=" + Build.MODEL + "\n"
                    + "fingerprint=" + Build.FINGERPRINT + "\n\n"
                    + stack.toString();
            FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), FILE_NAME), false);
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) {}
    }
}
