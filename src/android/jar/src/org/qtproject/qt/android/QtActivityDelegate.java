// Copyright (C) 2017 BogDan Vatra <bogdan@kde.org>
// Copyright (C) 2023 The Qt Company Ltd.
// Copyright (C) 2016 Olivier Goffart <ogoffart@woboq.com>
// SPDX-License-Identifier: LicenseRef-Qt-Commercial OR LGPL-3.0-only OR GPL-2.0-only OR GPL-3.0-only

package org.qtproject.qt.android;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.PopupMenu;

import java.util.ArrayList;
import java.util.HashMap;

import org.qtproject.qt.android.accessibility.QtAccessibilityDelegate;

public class QtActivityDelegate
{
    private Activity m_activity = null;

    private boolean m_started = false;
    private boolean m_quitApp = true;
    private boolean m_isPluginRunning = false;

    private HashMap<Integer, QtSurface> m_surfaces = null;
    private HashMap<Integer, View> m_nativeViews = null;
    private QtLayout m_layout = null;
    private ImageView m_splashScreen = null;
    private boolean m_splashScreenSticky = false;

    private View m_dummyView = null;

    private QtAccessibilityDelegate m_accessibilityDelegate = null;
    private final QtDisplayManager m_displayManager = new QtDisplayManager();

    private QtInputDelegate m_inputDelegate = null;

    QtActivityDelegate(Activity activity)
    {
        m_activity = activity;
        QtNative.setActivity(m_activity);

        setActionBarVisibility(false);

        m_displayManager.registerDisplayListener(m_activity, m_layout);

        QtInputDelegate.KeyboardVisibilityListener keyboardVisibilityListener =
                new QtInputDelegate.KeyboardVisibilityListener() {
            @Override
            public void onKeyboardVisibilityChange() {
                m_displayManager.updateFullScreen(m_activity);
            }
        };
        m_inputDelegate = new QtInputDelegate(m_activity, keyboardVisibilityListener);

        try {
            PackageManager pm = m_activity.getPackageManager();
            ActivityInfo activityInfo =  pm.getActivityInfo(m_activity.getComponentName(), 0);
            m_inputDelegate.setSoftInputMode(activityInfo.softInputMode);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
    }

    QtDisplayManager displayManager() {
        return m_displayManager;
    }

    QtInputDelegate getInputDelegate() {
        return m_inputDelegate;
    }

    QtLayout getQtLayout()
    {
        return m_layout;
    }

    public void setSystemUiVisibility(int systemUiVisibility)
    {
        QtNative.runAction(new Runnable() {
            @Override
            public void run() {
                m_displayManager.setSystemUiVisibility(m_activity, systemUiVisibility);
                m_layout.requestLayout();
                QtNative.updateWindow();
            }
        });
    }

    void setStarted(boolean started)
    {
        m_started = started;
    }

    boolean isStarted()
    {
        return m_started;
    }

    void setQuitApp(boolean quitApp)
    {
        m_quitApp = quitApp;
    }

    boolean isQuitApp()
    {
        return m_quitApp;
    }

    boolean isPluginRunning()
    {
        return m_isPluginRunning;
    }

    void setContextMenuVisible(boolean contextMenuVisible)
    {
        m_contextMenuVisible = contextMenuVisible;
    }

    boolean isContextMenuVisible()
    {
        return m_contextMenuVisible;
    }

    public boolean updateActivityAfterRestart(Activity activity) {
        try {
            // set new activity
            m_activity = activity;
            QtNative.setActivity(m_activity);

            // update the new activity content view to old layout
            ViewGroup layoutParent = (ViewGroup) m_layout.getParent();
            if (layoutParent != null)
                layoutParent.removeView(m_layout);

            m_activity.setContentView(m_layout);

            // force c++ native activity object to update
            return QtNative.updateNativeActivity();
        } catch (Exception e) {
            Log.w(QtNative.QtTAG, "Failed to update the activity.");
            e.printStackTrace();
            return false;
        }
    }

    public void onTerminate() {
        QtNative.terminateQt();
        QtNative.m_qtThread.exit();
    }

    public void startNativeApplication(ArrayList<String> appParams, String mainLib)
    {
        if (m_surfaces != null)
            return;

        Runnable startApplication = new Runnable() {
            @Override
            public void run() {
                try {
                    QtNative.startApplication(appParams, mainLib);
                    m_started = true;
                } catch (Exception e) {
                    e.printStackTrace();
                    m_activity.finish();
                }
            }
        };

        initMembers(startApplication);
    }
    
    private void initMembers(Runnable startApplicationRunnable)
    {
        m_quitApp = true;

        m_layout = new QtLayout(m_activity, startApplicationRunnable);

        int orientation = m_activity.getResources().getConfiguration().orientation;

        try {
            ActivityInfo info = m_activity.getPackageManager().getActivityInfo(m_activity.getComponentName(), PackageManager.GET_META_DATA);

            String splashScreenKey = "android.app.splash_screen_drawable_"
                + (orientation == Configuration.ORIENTATION_LANDSCAPE ? "landscape" : "portrait");
            if (!info.metaData.containsKey(splashScreenKey))
                splashScreenKey = "android.app.splash_screen_drawable";

            if (info.metaData.containsKey(splashScreenKey)) {
                m_splashScreenSticky = info.metaData.containsKey("android.app.splash_screen_sticky") && info.metaData.getBoolean("android.app.splash_screen_sticky");
                int id = info.metaData.getInt(splashScreenKey);
                m_splashScreen = new ImageView(m_activity);
                m_splashScreen.setImageDrawable(m_activity.getResources().getDrawable(id, m_activity.getTheme()));
                m_splashScreen.setScaleType(ImageView.ScaleType.FIT_XY);
                m_splashScreen.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                m_layout.addView(m_splashScreen);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        m_surfaces =  new HashMap<Integer, QtSurface>();
        m_nativeViews = new HashMap<Integer, View>();
        m_activity.registerForContextMenu(m_layout);
        m_activity.setContentView(m_layout,
                                  new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                             ViewGroup.LayoutParams.MATCH_PARENT));

        int rotation = m_activity.getWindowManager().getDefaultDisplay().getRotation();
        int nativeOrientation = QtDisplayManager.getNativeOrientation(m_activity, rotation);
        m_layout.setNativeOrientation(nativeOrientation);
        QtDisplayManager.handleOrientationChanged(rotation, nativeOrientation);

        handleUiModeChange(m_activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK);

        float refreshRate = (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
                ? m_activity.getWindowManager().getDefaultDisplay().getRefreshRate()
                : m_activity.getDisplay().getRefreshRate();
        QtDisplayManager.handleRefreshRateChanged(refreshRate);

        m_layout.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (!m_inputDelegate.isKeyboardVisible())
                    return true;

                Rect r = new Rect();
                m_activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(r);
                DisplayMetrics metrics = new DisplayMetrics();
                m_activity.getWindowManager().getDefaultDisplay().getMetrics(metrics);
                final int kbHeight = metrics.heightPixels - r.bottom;
                if (kbHeight < 0) {
                    m_inputDelegate.setKeyboardVisibility(false, System.nanoTime());
                    return true;
                }
                final int[] location = new int[2];
                m_layout.getLocationOnScreen(location);
                QtInputDelegate.keyboardGeometryChanged(location[0], r.bottom - location[1],
                                                 r.width(), kbHeight);
                return true;
            }
        });
        m_inputDelegate.setEditPopupMenu(new EditPopupMenu(m_activity, m_layout));
    }

    public void hideSplashScreen()
    {
        hideSplashScreen(0);
    }

    public void hideSplashScreen(final int duration)
    {
        QtNative.runAction(new Runnable() {
            @Override
            public void run() {
                if (m_splashScreen == null)
                    return;

                if (duration <= 0) {
                    m_layout.removeView(m_splashScreen);
                    m_splashScreen = null;
                    return;
                }

                final Animation fadeOut = new AlphaAnimation(1, 0);
                fadeOut.setInterpolator(new AccelerateInterpolator());
                fadeOut.setDuration(duration);

                fadeOut.setAnimationListener(new Animation.AnimationListener() {
                    @Override
                    public void onAnimationEnd(Animation animation) {
                        hideSplashScreen(0);
                    }

                    @Override
                    public void onAnimationRepeat(Animation animation) {
                    }

                    @Override
                    public void onAnimationStart(Animation animation) {
                    }
                });

                m_splashScreen.startAnimation(fadeOut);
            }
        });
    }

    public void notifyLocationChange(int viewId)
    {
        if (m_accessibilityDelegate == null)
            return;
        m_accessibilityDelegate.notifyLocationChange(viewId);
    }

    public void notifyObjectHide(int viewId, int parentId)
    {
        if (m_accessibilityDelegate == null)
            return;
        m_accessibilityDelegate.notifyObjectHide(viewId, parentId);
    }

    public void notifyObjectFocus(int viewId)
    {
        if (m_accessibilityDelegate == null)
            return;
        m_accessibilityDelegate.notifyObjectFocus(viewId);
    }

    public void notifyValueChanged(int viewId, String value)
    {
        if (m_accessibilityDelegate == null)
            return;
        m_accessibilityDelegate.notifyValueChanged(viewId, value);
    }

    public void notifyScrolledEvent(int viewId)
    {
        if (m_accessibilityDelegate == null)
            return;
        m_accessibilityDelegate.notifyScrolledEvent(viewId);
    }

    public void notifyQtAndroidPluginRunning(boolean running)
    {
        m_isPluginRunning = running;
    }

    public void initializeAccessibility()
    {
        final QtActivityDelegate currentDelegate = this;
        QtNative.runAction(new Runnable() {
            @Override
            public void run() {
                m_accessibilityDelegate = new QtAccessibilityDelegate(m_activity, m_layout,
                        currentDelegate);
            }
        });
    }

    void handleUiModeChange(int uiMode)
    {
        // QTBUG-108365
        if (Build.VERSION.SDK_INT >= 30) {
            // Since 29 version we are using Theme_DeviceDefault_DayNight
            Window window = m_activity.getWindow();
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                // set APPEARANCE_LIGHT_STATUS_BARS if needed
                int appearanceLight = Color.luminance(window.getStatusBarColor()) > 0.5 ?
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0;
                controller.setSystemBarsAppearance(appearanceLight,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        }
        switch (uiMode) {
            case Configuration.UI_MODE_NIGHT_NO:
                ExtractStyle.runIfNeeded(m_activity, false);
                QtDisplayManager.handleUiDarkModeChanged(0);
                break;
            case Configuration.UI_MODE_NIGHT_YES:
                ExtractStyle.runIfNeeded(m_activity, true);
                QtDisplayManager.handleUiDarkModeChanged(1);
                break;
        }
    }

    public void resetOptionsMenu()
    {
        QtNative.runAction(new Runnable() {
            @Override
            public void run() {
                m_activity.invalidateOptionsMenu();
            }
        });
    }

    public void openOptionsMenu()
    {
        QtNative.runAction(new Runnable() {
            @Override
            public void run() {
                m_activity.openOptionsMenu();
            }
        });
    }

    private boolean m_contextMenuVisible = false;

    public void onCreatePopupMenu(Menu menu)
    {
        QtNative.fillContextMenu(menu);
        m_contextMenuVisible = true;
    }

    public void openContextMenu(final int x, final int y, final int w, final int h)
    {
        m_layout.postDelayed(new Runnable() {
            @Override
            public void run() {
                m_layout.setLayoutParams(m_inputDelegate.getQtEditText(), new QtLayout.LayoutParams(w, h, x, y), false);
                PopupMenu popup = new PopupMenu(m_activity, m_inputDelegate.getQtEditText());
                QtActivityDelegate.this.onCreatePopupMenu(popup.getMenu());
                popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem menuItem) {
                        return m_activity.onContextItemSelected(menuItem);
                    }
                });
                popup.setOnDismissListener(new PopupMenu.OnDismissListener() {
                    @Override
                    public void onDismiss(PopupMenu popupMenu) {
                        m_activity.onContextMenuClosed(popupMenu.getMenu());
                    }
                });
                popup.show();
            }
        }, 100);
    }

    public void closeContextMenu()
    {
        QtNative.runAction(new Runnable() {
            @Override
            public void run() {
                m_activity.closeContextMenu();
            }
        });
    }

    void setActionBarVisibility(boolean visible)
    {
        if (m_activity.getActionBar() == null)
            return;
        if (ViewConfiguration.get(m_activity).hasPermanentMenuKey() || !visible)
            m_activity.getActionBar().hide();
        else
            m_activity.getActionBar().show();
    }

    public void insertNativeView(int id, View view, int x, int y, int w, int h) {
    QtNative.runAction(new Runnable() {
            @Override
            public void run() {
                if (m_dummyView != null) {
                    m_layout.removeView(m_dummyView);
                    m_dummyView = null;
                }

                if (m_nativeViews.containsKey(id))
                    m_layout.removeView(m_nativeViews.remove(id));

                if (w < 0 || h < 0) {
                    view.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
                } else {
                    view.setLayoutParams(new QtLayout.LayoutParams(w, h, x, y));
                }

                view.setId(id);
                m_layout.addView(view);
                m_nativeViews.put(id, view);
            }
        });
    }

    public void createSurface(int id, boolean onTop, int x, int y, int w, int h, int imageDepth) {
        QtNative.runAction(new Runnable() {
            @Override
            public void run() {
                if (m_surfaces.size() == 0) {
                    TypedValue attr = new TypedValue();
                    m_activity.getTheme().resolveAttribute(android.R.attr.windowBackground, attr, true);
                    if (attr.type >= TypedValue.TYPE_FIRST_COLOR_INT && attr.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                        m_activity.getWindow().setBackgroundDrawable(new ColorDrawable(attr.data));
                    } else {
                        m_activity.getWindow().setBackgroundDrawable(m_activity.getResources().getDrawable(attr.resourceId, m_activity.getTheme()));
                    }
                    if (m_dummyView != null) {
                        m_layout.removeView(m_dummyView);
                        m_dummyView = null;
                    }
                }

                if (m_surfaces.containsKey(id))
                    m_layout.removeView(m_surfaces.remove(id));

                QtSurface surface = new QtSurface(m_activity, id, onTop, imageDepth);
                if (w < 0 || h < 0) {
                    surface.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
                } else {
                    surface.setLayoutParams(new QtLayout.LayoutParams(w, h, x, y));
                }

                // Native views are always inserted in the end of the stack (i.e., on top).
                // All other views are stacked based on the order they are created.
                final int surfaceCount = getSurfaceCount();
                m_layout.addView(surface, surfaceCount);

                m_surfaces.put(id, surface);
                if (!m_splashScreenSticky)
                    hideSplashScreen();
            }
        });
    }

    public void setSurfaceGeometry(int id, int x, int y, int w, int h) {
        QtNative.runAction(new Runnable() {
            @Override
            public void run() {
                if (m_surfaces.containsKey(id)) {
                    QtSurface surface = m_surfaces.get(id);
                    surface.setLayoutParams(new QtLayout.LayoutParams(w, h, x, y));
                } else if (m_nativeViews.containsKey(id)) {
                    View view = m_nativeViews.get(id);
                    view.setLayoutParams(new QtLayout.LayoutParams(w, h, x, y));
                } else {
                    Log.e(QtNative.QtTAG, "Surface " + id + " not found!");
                    return;
                }
            }
        });
    }

    public void destroySurface(int id) {
        QtNative.runAction(new Runnable() {
            @Override
            public void run() {
                View view = null;

                if (m_surfaces.containsKey(id)) {
                    view = m_surfaces.remove(id);
                } else if (m_nativeViews.containsKey(id)) {
                    view = m_nativeViews.remove(id);
                } else {
                    Log.e(QtNative.QtTAG, "Surface " + id + " not found!");
                }

                if (view == null)
                    return;

                // Keep last frame in stack until it is replaced to get correct
                // shutdown transition
                if (m_surfaces.size() == 0 && m_nativeViews.size() == 0) {
                    m_dummyView = view;
                } else {
                    m_layout.removeView(view);
                }
            }
        });
    }

    public int getSurfaceCount()
    {
        return m_surfaces.size();
    }

    public void bringChildToFront(int id)
    {
        QtNative.runAction(new Runnable() {
            @Override
            public void run() {
                View view = m_surfaces.get(id);
                if (view != null) {
                    final int surfaceCount = getSurfaceCount();
                    if (surfaceCount > 0)
                        m_layout.moveChild(view, surfaceCount - 1);
                    return;
                }

                view = m_nativeViews.get(id);
                if (view != null)
                    m_layout.moveChild(view, -1);
            }
        });
    }

    public void bringChildToBack(int id)
    {
        QtNative.runAction(new Runnable() {
            @Override
            public void run() {
                View view = m_surfaces.get(id);
                if (view != null) {
                    m_layout.moveChild(view, 0);
                    return;
                }

                view = m_nativeViews.get(id);
                if (view != null) {
                    final int index = getSurfaceCount();
                    m_layout.moveChild(view, index);
                }
            }
        });
    }
}
