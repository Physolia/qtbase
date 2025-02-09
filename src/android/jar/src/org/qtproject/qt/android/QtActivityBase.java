// Copyright (C) 2023 The Qt Company Ltd.
// SPDX-License-Identifier: LicenseRef-Qt-Commercial OR LGPL-3.0-only OR GPL-2.0-only OR GPL-3.0-only

package org.qtproject.qt.android;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Browser;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;

public class QtActivityBase extends Activity
{
    public String m_applicationParams = null;
    private boolean m_isCustomThemeSet = false;

    private QtActivityDelegate m_delegate;

    private void addReferrer(Intent intent)
    {
        final String extraSourceInfoKey = "org.qtproject.qt.android.sourceInfo";

        if (intent.getExtras() != null && intent.getExtras().getString(extraSourceInfoKey) != null)
            return;

        String browserApplicationId = "";
        if (intent.getExtras() != null)
            browserApplicationId = intent.getExtras().getString(Browser.EXTRA_APPLICATION_ID);

        String sourceInformation = "";
        if (browserApplicationId != null && !browserApplicationId.isEmpty()) {
            sourceInformation = browserApplicationId;
        } else {
            Uri referrer = getReferrer();
            if (referrer != null)
                sourceInformation = referrer.toString().replaceFirst("android-app://", "");
        }

        intent.putExtra(extraSourceInfoKey, sourceInformation);
    }

    // Append any parameters to your application,
    // the parameters must be "\t" separated.
    protected void appendApplicationParameters(String params)
    {
        m_applicationParams += params;
    }

    private void handleActivityRestart() {
        if (QtNative.isStarted()) {
            boolean updated = m_delegate.updateActivityAfterRestart(this);
            if (!updated) {
                // could not update the activity so restart the application
                Intent intent = Intent.makeRestartActivityTask(getComponentName());
                startActivity(intent);
                QtNative.quitApp();
                Runtime.getRuntime().exit(0);
            }
        }
    }

    @Override
    public void setTheme(int resId) {
        super.setTheme(resId);
        m_isCustomThemeSet = true;
    }

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_ACTION_BAR);

        if (!m_isCustomThemeSet) {
            setTheme(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ?
                    android.R.style.Theme_DeviceDefault_DayNight :
                    android.R.style.Theme_Holo_Light);
        }

        m_delegate = new QtActivityDelegate(this);

        handleActivityRestart();
        addReferrer(getIntent());

        QtActivityLoader loader = new QtActivityLoader(this);
        loader.setApplicationParameters(m_applicationParams);

        loader.loadQtLibraries();
        m_delegate.startNativeApplication(loader.getApplicationParameters(),
                loader.getMainLibrary());
    }

    @Override
    protected void onStart()
    {
        super.onStart();
    }

    @Override
    protected void onRestart()
    {
        super.onRestart();
    }

    @Override
    protected void onPause()
    {
        super.onPause();
        if (Build.VERSION.SDK_INT < 24 || !isInMultiWindowMode())
            QtNative.setApplicationState(QtConstants.ApplicationState.ApplicationInactive);
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        QtNative.setApplicationState(QtConstants.ApplicationState.ApplicationActive);
        if (m_delegate.isStarted()) {
            QtNative.updateWindow();
            // Suspending the app clears the immersive mode, so we need to set it again.
            m_delegate.displayManager().updateFullScreen(this);
        }
    }

    @Override
    protected void onStop()
    {
        super.onStop();
        QtNative.setApplicationState(QtConstants.ApplicationState.ApplicationSuspended);
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();
        if (m_delegate.isQuitApp()) {
            QtNative.terminateQt();
            QtNative.setActivity(null);
            QtNative.m_qtThread.exit();
            System.exit(0);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig)
    {
        super.onConfigurationChanged(newConfig);
        m_delegate.handleUiModeChange(newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item)
    {
        m_delegate.setContextMenuVisible(false);
        return QtNative.onContextItemSelected(item.getItemId(), item.isChecked());
    }

    @Override
    public void onContextMenuClosed(Menu menu)
    {
        if (!m_delegate.isContextMenuVisible())
            return;
        m_delegate.setContextMenuVisible(false);
        QtNative.onContextMenuClosed(menu);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo)
    {
        menu.clearHeader();
        QtNative.onCreateContextMenu(menu);
        m_delegate.setContextMenuVisible(true);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event)
    {
        if (m_delegate.isStarted() && m_delegate.getInputDelegate().handleDispatchKeyEvent(event))
            return true;

        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event)
    {
        boolean handled = m_delegate.getInputDelegate().handleDispatchGenericMotionEvent(event);
        if (m_delegate.isStarted() && handled)
            return true;

        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)
    {
        if (!m_delegate.isStarted() || !m_delegate.isPluginRunning())
            return false;

        return m_delegate.getInputDelegate().onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event)
    {
        if (!m_delegate.isStarted() || !m_delegate.isPluginRunning())
            return false;

        return m_delegate.getInputDelegate().onKeyUp(keyCode, event);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        menu.clear();
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu)
    {
        boolean res = QtNative.onPrepareOptionsMenu(menu);
        m_delegate.setActionBarVisibility(res && menu.size() > 0);
        return res;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        return QtNative.onOptionsItemSelected(item.getItemId(), item.isChecked());
    }

    @Override
    public void onOptionsMenuClosed(Menu menu)
    {
        QtNative.onOptionsMenuClosed(menu);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState)
    {
        super.onRestoreInstanceState(savedInstanceState);
        m_delegate.setStarted(savedInstanceState.getBoolean("Started"));
        // FIXME restore all surfaces
    }

    @Override
    public Object onRetainNonConfigurationInstance()
    {
        super.onRetainNonConfigurationInstance();
        m_delegate.setQuitApp(false);
        return true;

    }

    @Override
    protected void onSaveInstanceState(Bundle outState)
    {
        super.onSaveInstanceState(outState);
        outState.putInt("SystemUiVisibility", m_delegate.displayManager().systemUiVisibility());
        outState.putBoolean("Started", m_delegate.isStarted());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus)
    {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus)
            m_delegate.displayManager().updateFullScreen(this);
    }

    @Override
    protected void onNewIntent(Intent intent)
    {
        QtNative.onNewIntent(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);
        QtNative.onActivityResult(requestCode, resultCode, data);
    }

    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults)
    {
        QtNative.sendRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    public void hideSplashScreen(final int duration)
    {
        m_delegate.hideSplashScreen(duration);
    }

    QtActivityDelegate getActivityDelegate()
    {
        return m_delegate;
    }
}
