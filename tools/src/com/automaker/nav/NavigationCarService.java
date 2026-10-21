package com.automaker.nav;

import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.view.Surface;

import androidx.car.app.AppManager;
import androidx.car.app.CarAppService;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.SessionInfo;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.NavigationManager;
import androidx.car.app.navigation.NavigationManagerCallback;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.validation.HostValidator;

public class NavigationCarService extends CarAppService {
    @Override
    public HostValidator createHostValidator() {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }

    @Override
    public Session onCreateSession(SessionInfo sessionInfo) {
        return new Session() {
            @Override
            public Screen onCreateScreen(Intent intent) {
                return new NavScreen(getCarContext());
            }
        };
    }

    private static final class NavScreen extends Screen implements SurfaceCallback {
        private VirtualDisplay display;
        private boolean started;

        NavScreen(CarContext context) {
            super(context);
            context.getCarService(AppManager.class).setSurfaceCallback(this);
        }

        @Override
        public Template onGetTemplate() {
            if (!started) claimNavigation();
            return new NavigationTemplate.Builder()
                    .setActionStrip(new ActionStrip.Builder().addAction(Action.PAN).build())
                    .build();
        }

        private void claimNavigation() {
            try {
                NavigationManager manager = getCarContext().getCarService(NavigationManager.class);
                manager.setNavigationManagerCallback(new NavigationManagerCallback() {
                    @Override
                    public void onStopNavigation() {
                        started = false;
                    }
                });
                manager.navigationStarted();
                started = true;
            } catch (RuntimeException ignored) {
                started = false;
            }
        }

        @Override
        public void onSurfaceAvailable(SurfaceContainer container) {
            Surface surface = container.getSurface();
            if (surface == null) return;
            try {
                DisplayManager displays = (DisplayManager) getCarContext().getSystemService(DISPLAY_SERVICE);
                if (displays == null) return;
                int dpi = container.getDpi() > 0 ? container.getDpi() : 160;
                display = displays.createVirtualDisplay(
                        "nav",
                        Math.max(container.getWidth(), 1),
                        Math.max(container.getHeight(), 1),
                        dpi,
                        surface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
                if (display == null || display.getDisplay() == null) return;
                Intent launch = getCarContext().getPackageManager().getLaunchIntentForPackage(getCarContext().getPackageName());
                if (launch == null) return;
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                if (Build.VERSION.SDK_INT >= 26) {
                    android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
                    options.setLaunchDisplayId(display.getDisplay().getDisplayId());
                    getCarContext().startActivity(launch, options.toBundle());
                }
            } catch (RuntimeException ignored) {
                if (display != null) {
                    display.release();
                    display = null;
                }
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceContainer container) {
            if (display != null) {
                display.release();
                display = null;
            }
        }
    }
}
