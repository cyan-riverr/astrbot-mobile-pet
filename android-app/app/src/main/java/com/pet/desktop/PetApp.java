package com.pet.desktop;

import android.app.Application;

public class PetApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashLogger.install(this);
    }
}
