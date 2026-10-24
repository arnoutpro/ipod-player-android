package com.arnoutpro.ipod3g;

import android.content.Context;

import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;
import com.google.android.gms.cast.framework.media.CastMediaOptions;

import java.util.List;

/**
 * Tells the Cast SDK how to cast: with Google's Default Media Receiver, so no app of our own has to
 * be registered, and without the SDK's notification and media session, since the player has its own.
 */
public class CastOptionsProvider implements OptionsProvider {
    public static final String RECEIVER = CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID;

    @Override
    public CastOptions getCastOptions(Context context) {
        return new CastOptions.Builder()
                .setReceiverApplicationId(RECEIVER)
                .setStopReceiverApplicationWhenEndingSession(true)
                .setCastMediaOptions(new CastMediaOptions.Builder()
                        .setNotificationOptions(null)
                        .setMediaSessionEnabled(false)
                        .build())
                .build();
    }

    @Override
    public List<SessionProvider> getAdditionalSessionProviders(Context context) {
        return null;
    }
}
