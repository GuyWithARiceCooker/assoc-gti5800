package tomi.assoc.gti5800;

import android.app.Activity;

/**
 * GT-I5800 (galaxy3) build: távoli önfrissítés AndroidX / FileProvider nélkül lehetetlen — üresen hagyva,
 * hogy a {@link AssocActivity} hívások ne törjenek. A `full` flavorban a fő {@code main} forrás érvényesül.
 */
public final class RemoteUpdateManager {
    public static final int RC_INSTALL_SETTINGS = 5101;

    private RemoteUpdateManager() {
    }

    public static void maybeAutoCheck(Activity act) {
    }

    public static void startManualCheck(Activity act) {
    }
}
