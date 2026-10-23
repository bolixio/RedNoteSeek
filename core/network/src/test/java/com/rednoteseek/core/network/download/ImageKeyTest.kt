package com.rednoteseek.core.network.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageKeyTest {

    @Test fun keepsNotesPrePostPrefix() {
        assertEquals(
            "notes_pre_post/1040g3k831rkjulrpi6fg5q5vcoamh4g2pm2o010",
            Downloader.imageKey("http://sns-webpic-qc.xhscdn.com/202610180249/c4fe123964bbf3ba636e3f45a9319ad3/notes_pre_post/1040g3k831rkjulrpi6fg5q5vcoamh4g2pm2o010!nd_dft_wlteh_webp_3"),
        )
    }

    @Test fun dropsTimestampAndSignature() {
        assertEquals(
            "1040g2sg324jl7cpf0a705on1taj7opj0aiisvhg",
            Downloader.imageKey("http://sns-webpic-qc.xhscdn.com/202610180239/b69fae18f717977f633cf8d6997f6d00/1040g2sg324jl7cpf0a705on1taj7opj0aiisvhg!nc_n_webp_mw_1"),
        )
    }

    @Test fun plainPath() {
        assertEquals("abc123", Downloader.imageKey("https://sns-img-qc.xhscdn.com/abc123"))
    }

    @Test fun emptyPathIsNull() {
        assertNull(Downloader.imageKey("https://sns-img-qc.xhscdn.com/"))
    }
}
