package com.zwm.gallery;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import com.zwm.livecore.LiveConvertResult;
import com.zwm.livecore.LiveMotionPhotoConverter;
import com.zwm.livecore.LiveQualityTier;
import java.io.File;
import java.io.IOException;

/**
 * Shared live-photo engine delegate for 团队工作相册.
 * All conversion runs through com.zwm.livecore.LiveMotionPhotoConverter (lossless JPEG passthrough + 3 quality tiers).
 */
public final class LiveLocalConverter {
    private LiveLocalConverter() {
    }

    public static boolean isAlreadyMotionPhoto(ContentResolver resolver, Uri uri) {
        return LiveMotionPhotoConverter.isAlreadyMotionPhoto(resolver, uri);
    }

    public static boolean isAlreadyMotionPhoto(File file) {
        return LiveMotionPhotoConverter.isAlreadyMotionPhoto(file);
    }

    public static LiveConvertResult convertUriDetailed(
            Context context,
            Uri sourceUri,
            String modeId,
            LiveQualityTier tier,
            float ratio,
            float durationSec,
            int index,
            boolean skipExistingLive
    ) throws IOException {
        return LiveMotionPhotoConverter.convertUriDetailed(
                context, sourceUri, modeId, tier, ratio, durationSec, index, skipExistingLive);
    }

    public static Uri convertUriOrPassthrough(
            Context context,
            Uri sourceUri,
            String modeId,
            float ratio,
            float durationSec,
            int index,
            boolean skipExistingLive
    ) throws IOException {
        LiveQualityTier tier = LiveQualityTier.fromId(OnlineService.getAutoLiveQualityTier(context));
        return convertUriDetailed(context, sourceUri, modeId, tier, ratio, durationSec, index, skipExistingLive).outputUri;
    }

    public static LiveConvertResult convertFileToFileDetailed(
            Context context,
            File sourceFile,
            File targetFile,
            String modeId,
            LiveQualityTier tier,
            float ratio,
            float durationSec,
            boolean skipExistingLive
    ) throws IOException {
        return LiveMotionPhotoConverter.convertFileToFileDetailed(
                context, sourceFile, targetFile, modeId, tier, ratio, durationSec, skipExistingLive);
    }

    public static File convertFileToFile(
            Context context,
            File sourceFile,
            File targetFile,
            String modeId,
            float ratio,
            float durationSec,
            boolean skipExistingLive
    ) throws IOException {
        LiveQualityTier tier = LiveQualityTier.fromId(OnlineService.getAutoLiveQualityTier(context));
        return convertFileToFileDetailed(
                context, sourceFile, targetFile, modeId, tier, ratio, durationSec, skipExistingLive).outputFile;
    }
}
