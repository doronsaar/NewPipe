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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Helper class to enforce audio-only mode for videos unless their title matches
 * one of the regex patterns defined in audio_only_exceptions.json.
 * <p>
 * The JSON file is located at:
 * {@code /storage/emulated/0/Android/data/[package]/files/audio_only_exceptions.json}
 * <p>
 * If the file doesn't exist, it will be created with an example pattern.
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
    private static final String DEFAULT_CONFIG_CONTENT =
            "{\n  \"patterns\": [\n    \"put_your_regex_here\"\n  ]\n}\n";

    // Cached patterns - reloaded when file changes
    private static List<Pattern> cachedPatterns = null;
    private static long lastModified = 0;
    private static File configFile = null;

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
     * Creates the config file with an example pattern if it doesn't exist.
     *
     * @param context the application context
     * @return list of compiled regex patterns
     */
    @NonNull
    private static synchronized List<Pattern> loadPatterns(@NonNull final Context context) {
        if (configFile == null) {
            // Use external files directory (accessible to users)
            final File externalDir = context.getExternalFilesDir(null);
            if (externalDir != null) {
                configFile = new File(externalDir, CONFIG_FILE_NAME);
                // Create default config file if it doesn't exist
                if (!configFile.exists()) {
                    createDefaultConfigFile(configFile);
                }
            }
        }

        // If no config file available, return empty list
        if (configFile == null || !configFile.exists()) {
            Log.e(TAG, "Cannot access config file location");
            cachedPatterns = new ArrayList<>();
            return cachedPatterns;
        }

        // Check if file was modified since last load
        final long currentModified = configFile.lastModified();
        if (cachedPatterns != null && currentModified == lastModified) {
            return cachedPatterns;
        }
        lastModified = currentModified;

        // Reload patterns
        cachedPatterns = new ArrayList<>();

        try (InputStream is = new FileInputStream(configFile)) {
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

            Log.d(TAG, "Loaded " + cachedPatterns.size() + " patterns from: "
                    + configFile.getAbsolutePath());
        } catch (final IOException | JsonParserException e) {
            Log.e(TAG, "Error reading config file", e);
            cachedPatterns = new ArrayList<>();
        }

        return cachedPatterns;
    }

    /**
     * Creates the default config file with an example pattern.
     *
     * @param file the file to create
     */
    private static void createDefaultConfigFile(@NonNull final File file) {
        try {
            final File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8)) {
                writer.write(DEFAULT_CONFIG_CONTENT);
            }
            Log.d(TAG, "Created default config file: " + file.getAbsolutePath());
        } catch (final IOException e) {
            Log.e(TAG, "Failed to create default config file", e);
        }
    }

    /**
     * Clears the cached patterns, forcing a reload on next check.
     */
    public static synchronized void clearCache() {
        cachedPatterns = null;
        lastModified = 0;
        configFile = null;
    }
}

