package com.zwm.livestudio;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import com.zwm.livecore.LiveConvertResult;
import com.zwm.livecore.LiveMotionPhotoConverter;
import com.zwm.livecore.LiveQualityTier;
import java.io.IOException;

/**
 * Shared live-photo engine delegate for 独立实况工坊.
 * Uses the exact same lossless-static & 3-tier hardware video engine as 团队工作相册.
 */
public final class LivePhotoConverter {
    private LivePhotoConverter() {
    }

    public static boolean isAlreadyMotionPhoto(ContentResolver resolver, Uri uri) {
        return LiveMotionPhotoConverter.isAlreadyMotionPhoto(resolver, uri);
    }

    public static LiveConvertResult convertOrPassthroughDetailed(
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

    public static LiveConvertResult saveExternalMotionPhoto(
            Context context,
            byte[] motionPhotoBytes,
            int index
    ) throws IOException {
        return LiveMotionPhotoConverter.savePackedMotionPhotoToGallery(context, motionPhotoBytes, index);
    }

    public static Uri convertOrPassthrough(
            Context context,
            Uri sourceUri,
            String modeId,
            float ratio,
            float durationSec,
            int index,
            boolean skipExistingLive
    ) throws IOException {
        return convertOrPassthroughDetailed(
                context, sourceUri, modeId, LiveQualityTier.HD, ratio, durationSec, index, skipExistingLive
        ).outputUri;
    }

    public static Uri convertAndSave(
            Context context,
            Uri sourceUri,
            String modeId,
            float ratio,
            float durationSec,
            int index
    ) throws IOException {
        return convertOrPassthroughDetailed(
                context, sourceUri, modeId, LiveQualityTier.HD, ratio, durationSec, index, false
        ).outputUri;
    }
}
