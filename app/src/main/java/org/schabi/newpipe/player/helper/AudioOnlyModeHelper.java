package org.schabi.newpipe.player.helper;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Helper class to enforce audio-only mode for videos unless their title matches
 * one of the regex patterns defined in audio_only_exceptions.json.
 * <p>
 * The JSON file is loaded from (in order of priority):
 * <ol>
 *     <li>App's external files directory (Android/data/[package]/files/)</li>
 *     <li>App's internal files directory</li>
 *     <li>Bundled assets (default patterns)</li>
 * </ol>
 * <p>
 * File format:
 * <pre>
 * {
 *   "patterns": ["regex1", "regex2", ...]
 * }
 * </pre>
 * <p>
 * Videos are only displayed if their title matches at least one pattern.
 * Patterns are case-insensitive.
 */
public final class AudioOnlyModeHelper {
    private static final String TAG = AudioOnlyModeHelper.class.getSimpleName();
    private static final String CONFIG_FILE_NAME = "audio_only_exceptions.json";
    private static final String JSON_PATTERNS_KEY = "patterns";

    // Cached patterns - reloaded when file changes
    private static List<Pattern> cachedPatterns = null;
    private static long lastModified = 0;
    private static File configFile = null;
    private static boolean useAssets = false;

    private AudioOnlyModeHelper() {
    }

    /**
     * Checks if video should be displayed for the given title.
     * Video is displayed only if the title matches at least one regex pattern
     * from the audio_only_exceptions.json file.
     *
     * @param context the application context
     * @param title   the video title to check
     * @return true if video should be displayed, false for audio-only mode
     */
    public static boolean shouldDisplayVideo(@NonNull final Context context,
                                             @Nullable final String title) {
        if (title == null || title.isEmpty()) {
            return false;
        }

        final List<Pattern> patterns = loadPatterns(context);
        if (patterns.isEmpty()) {
            // No patterns defined - all videos are audio-only
            return false;
        }

        for (final Pattern pattern : patterns) {
            try {
                if (pattern.matcher(title).find()) {
                    return true;
                }
            } catch (final Exception e) {
                Log.e(TAG, "Error matching pattern: " + pattern.pattern(), e);
            }
        }

        return false;
    }

    /**
     * Loads regex patterns from the config file, using cache if file hasn't changed.
     * Checks external files directory first, then internal files, then bundled assets.
     *
     * @param context the application context
     * @return list of compiled regex patterns
     */
    @NonNull
    private static synchronized List<Pattern> loadPatterns(@NonNull final Context context) {
        if (configFile == null && !useAssets) {
            // Try external files directory first (more accessible to users)
            final File externalDir = context.getExternalFilesDir(null);
            if (externalDir != null) {
                final File externalFile = new File(externalDir, CONFIG_FILE_NAME);
                if (externalFile.exists()) {
                    configFile = externalFile;
                }
            }
            // Fall back to internal files directory
            if (configFile == null) {
                final File internalFile = new File(context.getFilesDir(), CONFIG_FILE_NAME);
                if (internalFile.exists()) {
                    configFile = internalFile;
                }
            }
            // Fall back to bundled assets
            if (configFile == null) {
                useAssets = true;
            }
        }

        // If using assets and already cached, return cache
        if (useAssets && cachedPatterns != null) {
            return cachedPatterns;
        }

        // If using file, check if it was modified since last load
        if (!useAssets) {
            final long currentModified = configFile.lastModified();
            if (cachedPatterns != null && currentModified == lastModified) {
                return cachedPatterns;
            }
            lastModified = currentModified;
        }

        // Reload patterns
        cachedPatterns = new ArrayList<>();

        try {
            final InputStream is;
            final String source;
            if (useAssets) {
                is = context.getAssets().open(CONFIG_FILE_NAME);
                source = "assets";
            } else {
                is = new FileInputStream(configFile);
                source = configFile.getAbsolutePath();
            }

            try {
                final JsonArray patternsArray = JsonParser.object()
                        .from(is)
                        .getArray(JSON_PATTERNS_KEY);

                if (patternsArray != null) {
                    for (final Object obj : patternsArray) {
                        if (obj instanceof String) {
                            try {
                                cachedPatterns.add(Pattern.compile((String) obj,
                                        Pattern.CASE_INSENSITIVE));
                            } catch (final PatternSyntaxException e) {
                                Log.e(TAG, "Invalid regex pattern: " + obj, e);
                            }
                        }
                    }
                }

                Log.d(TAG, "Loaded " + cachedPatterns.size() + " patterns from: " + source);
            } finally {
                is.close();
            }
        } catch (final IOException | JsonParserException e) {
            Log.e(TAG, "Error reading config file", e);
            cachedPatterns = new ArrayList<>();
        }

        return cachedPatterns;
    }

    /**
     * Clears the cached patterns, forcing a reload on next check.
     */
    public static synchronized void clearCache() {
        cachedPatterns = null;
        lastModified = 0;
        configFile = null;
        useAssets = false;
    }
}

