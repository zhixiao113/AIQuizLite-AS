package com.example.aiquizlite;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QuestionImageStoreTest {
    @Test
    public void localReferencesAreRecognized() {
        assertTrue(QuestionImageStore.isLocal("local:image.jpg"));
        assertFalse(QuestionImageStore.isLocal("legacy-asset.jpg"));
    }

    @Test
    public void sampleSizeKeepsDecodeNearTargetWithoutOverDecoding() {
        assertEquals(1, QuestionImageStore.calculateInSampleSize(1600, 900, 1600));
        assertEquals(2, QuestionImageStore.calculateInSampleSize(6000, 4000, 1600));
        assertEquals(4, QuestionImageStore.calculateInSampleSize(12000, 8000, 1600));
    }
}
