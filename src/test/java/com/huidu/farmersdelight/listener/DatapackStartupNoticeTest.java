package com.huidu.farmersdelight.listener;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The restart hint of a datapack install is announced once, after the startup exports have settled. */
class DatapackStartupNoticeTest {

    @Test
    void aStartupWriteIsSilentUntilFinishAnnouncesIt() {
        DatapackStartupNotice notice = new DatapackStartupNotice();

        assertFalse(notice.record(true), "the enable-time write must not announce yet");
        assertTrue(notice.finish(false), "the post-startup export announces the deferred write");
    }

    @Test
    void aStartupWithoutWritesAnnouncesNothing() {
        DatapackStartupNotice notice = new DatapackStartupNotice();

        assertFalse(notice.record(false));
        assertFalse(notice.finish(false));
    }

    @Test
    void thePostStartupWriteIsAnnouncedOnce() {
        DatapackStartupNotice notice = new DatapackStartupNotice();
        assertFalse(notice.record(true), "enable-time write is deferred");

        assertTrue(notice.finish(true), "the post-startup write is the one that announces");
        assertFalse(notice.finish(false), "a finished notice never repeats");
    }

    @Test
    void aReloadAfterStartupAnnouncesItsOwnWrite() {
        DatapackStartupNotice notice = new DatapackStartupNotice();
        notice.finish(false);

        assertTrue(notice.record(true), "after startup a changed pack is announced immediately");
        assertFalse(notice.record(false), "an unchanged pack stays silent");
    }
}
