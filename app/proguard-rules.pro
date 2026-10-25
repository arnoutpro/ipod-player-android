# R8 shrinks the release build. The libraries bring their own rules; these cover what R8 can't
# see on its own.

# Named only in the manifest (Cast's OPTIONS_PROVIDER_CLASS_NAME), never in code.
-keep class com.arnoutpro.ipod3g.CastOptionsProvider { *; }

# Shrink and optimise, but keep the names, so crash logs stay readable.
-dontobfuscate

# Spotify's App Remote SDK can map with Jackson, but this app uses its Gson mapper, so Jackson is
# never loaded; its annotations aren't shipped either.
-dontwarn com.fasterxml.jackson.**
-dontwarn com.spotify.base.annotations.**
