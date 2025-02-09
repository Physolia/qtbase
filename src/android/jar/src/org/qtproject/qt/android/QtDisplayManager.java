// Copyright (C) 2023 The Qt Company Ltd.
// SPDX-License-Identifier: LicenseRef-Qt-Commercial OR LGPL-3.0-only OR GPL-2.0-only OR GPL-3.0-only

package org.qtproject.qt.android;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class QtDisplayManager {

    // screen methods
    public static native void setDisplayMetrics(int screenWidthPixels, int screenHeightPixels,
                                                int availableLeftPixels, int availableTopPixels,
                                                int availableWidthPixels, int availableHeightPixels,
                                                double XDpi, double YDpi, double scaledDensity,
                                                double density, float refreshRate);
    public static native void handleOrientationChanged(int newRotation, int nativeOrientation);
    public static native void handleRefreshRateChanged(float refreshRate);
    public static native void handleUiDarkModeChanged(int newUiMode);
    public static native void handleScreenAdded(int displayId);
    public static native void handleScreenChanged(int displayId);
    public static native void handleScreenRemoved(int displayId);
    // screen methods

    // Keep in sync with QtAndroid::SystemUiVisibility in androidjnimain.h
    public static final int SYSTEM_UI_VISIBILITY_NORMAL = 0;
    public static final int SYSTEM_UI_VISIBILITY_FULLSCREEN = 1;
    public static final int SYSTEM_UI_VISIBILITY_TRANSLUCENT = 2;
    private int m_systemUiVisibility = SYSTEM_UI_VISIBILITY_NORMAL;

    // FIXME: some methods here make more sense as non-static, fix that
    QtDisplayManager() {}

    // TODO: unregister the listener upon activity destruction as well
    public void registerDisplayListener(Activity activity, QtLayout layout)
    {
        DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener()
        {
            @Override
            public void onDisplayAdded(int displayId) {
                QtDisplayManager.handleScreenAdded(displayId);
            }

            private boolean isSimilarRotation(int r1, int r2)
            {
                return (r1 == r2)
                        || (r1 == Surface.ROTATION_0 && r2 == Surface.ROTATION_180)
                        || (r1 == Surface.ROTATION_180 && r2 == Surface.ROTATION_0)
                        || (r1 == Surface.ROTATION_90 && r2 == Surface.ROTATION_270)
                        || (r1 == Surface.ROTATION_270 && r2 == Surface.ROTATION_90);
            }

            @Override
            public void onDisplayChanged(int displayId)
            {
                Display display = (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
                        ? activity.getWindowManager().getDefaultDisplay()
                        : activity.getDisplay();
                int rotation = display.getRotation();
                layout.setActivityDisplayRotation(rotation);
                // Process orientation change only if it comes after the size
                // change, or if the screen is rotated by 180 degrees.
                // Otherwise it will be processed in QtLayout.
                if (isSimilarRotation(rotation, layout.displayRotation())) {
                    QtDisplayManager.handleOrientationChanged(rotation,
                            getNativeOrientation(activity, rotation));
                }

                float refreshRate = display.getRefreshRate();
                QtDisplayManager.handleRefreshRateChanged(refreshRate);
                QtDisplayManager.handleScreenChanged(displayId);
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                QtDisplayManager.handleScreenRemoved(displayId);
            }
        };

        DisplayManager displayManager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
        displayManager.registerDisplayListener(displayListener, null);
    }

    public static int getNativeOrientation(Activity activity, int rotation)
    {
        int nativeOrientation;

        int orientation = activity.getResources().getConfiguration().orientation;
        boolean rot90 = (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270);
        boolean isLandscape = (orientation == Configuration.ORIENTATION_LANDSCAPE);
        if ((isLandscape && !rot90) || (!isLandscape && rot90))
            nativeOrientation = Configuration.ORIENTATION_LANDSCAPE;
        else
            nativeOrientation = Configuration.ORIENTATION_PORTRAIT;

        return nativeOrientation;
    }

    public void setSystemUiVisibility(Activity activity, int systemUiVisibility)
    {

        if (m_systemUiVisibility == systemUiVisibility)
            return;

        m_systemUiVisibility = systemUiVisibility;

        int systemUiVisibilityFlags = View.SYSTEM_UI_FLAG_VISIBLE;
        switch (m_systemUiVisibility) {
            case SYSTEM_UI_VISIBILITY_NORMAL:
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                setDisplayCutoutLayout(activity,
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER);
                break;
            case SYSTEM_UI_VISIBILITY_FULLSCREEN:
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                systemUiVisibilityFlags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.INVISIBLE;
                setDisplayCutoutLayout(activity,
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT);
                break;
            case SYSTEM_UI_VISIBILITY_TRANSLUCENT:
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
                        | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
                activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                setDisplayCutoutLayout(activity,
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS);
                break;
        }
        activity.getWindow().getDecorView().setSystemUiVisibility(systemUiVisibilityFlags);
    }

    public int systemUiVisibility()
    {
        return m_systemUiVisibility;
    }

    private void setDisplayCutoutLayout(Activity activity, int cutoutLayout)
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            activity.getWindow().getAttributes().layoutInDisplayCutoutMode = cutoutLayout;
    }

    public void updateFullScreen(Activity activity)
    {
        if (m_systemUiVisibility == SYSTEM_UI_VISIBILITY_FULLSCREEN) {
            m_systemUiVisibility = SYSTEM_UI_VISIBILITY_NORMAL;
            setSystemUiVisibility(activity, SYSTEM_UI_VISIBILITY_FULLSCREEN);
        }
    }

    public static Display getDisplay(Context context, int displayId)
    {
        DisplayManager displayManager =
                (DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager != null) {
            return displayManager.getDisplay(displayId);
        }
        return null;
    }

    public static List<Display> getAvailableDisplays(Context context)
    {
        DisplayManager displayManager =
                (DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager != null) {
            Display[] displays = displayManager.getDisplays();
            return Arrays.asList(displays);
        }
        return new ArrayList<>();
    }

    public static Size getDisplaySize(Context displayContext, Display display)
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            DisplayMetrics realMetrics = new DisplayMetrics();
            display.getRealMetrics(realMetrics);
            return new Size(realMetrics.widthPixels, realMetrics.heightPixels);
        }

        Context windowsContext = displayContext.createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION, null);
        WindowManager windowManager =
                (WindowManager) windowsContext.getSystemService(Context.WINDOW_SERVICE);
        WindowMetrics windowsMetrics = windowManager.getCurrentWindowMetrics();
        Rect bounds = windowsMetrics.getBounds();
        return new Size(bounds.width(), bounds.height());
    }

    public static void setApplicationDisplayMetrics(Activity activity, int width, int height)
    {
        if (activity == null)
            return;

        final WindowInsets rootInsets = activity.getWindow().getDecorView().getRootWindowInsets();
        final WindowManager windowManager = activity.getWindowManager();
        Display display;

        int insetLeft;
        int insetTop;

        int maxWidth;
        int maxHeight;

        if (android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            display = windowManager.getDefaultDisplay();

            final DisplayMetrics maxMetrics = new DisplayMetrics();
            display.getRealMetrics(maxMetrics);
            maxWidth = maxMetrics.widthPixels;
            maxHeight = maxMetrics.heightPixels;

            insetLeft = rootInsets.getStableInsetLeft();
            insetTop = rootInsets.getStableInsetTop();
        } else {
            display = activity.getDisplay();

            final WindowMetrics maxMetrics = windowManager.getMaximumWindowMetrics();
            maxWidth = maxMetrics.getBounds().width();
            maxHeight = maxMetrics.getBounds().height();

            insetLeft = rootInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()).left;
            insetTop = rootInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()).top;
        }

        final DisplayMetrics displayMetrics = activity.getResources().getDisplayMetrics();

        double xdpi = displayMetrics.xdpi;
        double ydpi = displayMetrics.ydpi;

        /* Fix buggy dpi report */
        if (xdpi < android.util.DisplayMetrics.DENSITY_LOW)
            xdpi = android.util.DisplayMetrics.DENSITY_LOW;
        if (ydpi < android.util.DisplayMetrics.DENSITY_LOW)
            ydpi = android.util.DisplayMetrics.DENSITY_LOW;

        double density = displayMetrics.density;
        double scaledDensity = displayMetrics.scaledDensity;

        float refreshRate = 60.0f;
        if (display != null) {
            refreshRate = display.getRefreshRate();
        }

        setDisplayMetrics(maxWidth, maxHeight, insetLeft, insetTop,
                width, height, xdpi, ydpi,
                scaledDensity, density, refreshRate);
    }

    public static int getDisplayRotation(Activity activity) {
        Display display;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            final WindowManager windowManager = activity.getWindowManager();
            display = windowManager.getDefaultDisplay();
        } else {
            display = activity.getDisplay();
        }

        int newRotation = 0;
        if (display != null) {
            newRotation = display.getRotation();
        }
        return newRotation;
    }
}
