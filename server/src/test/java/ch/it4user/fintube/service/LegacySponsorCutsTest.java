package ch.it4user.fintube.service;

import ch.it4user.fintube.media.MediaSourceService;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LegacySponsorCutsTest {
    @Test
    void wholeFragmentPairsInsideSponsorsAreDroppedAndEncodedInTheRoute() {
        List<MediaSourceService.Fragment> parts = new ArrayList<>();
        for (int i = 0; i < 6; i++)
            parts.add(new MediaSourceService.Fragment("v" + i, URI.create("https://v/" + i), URI.create("https://a/" + i), 5, i * 5));

        String cuts = PlaybackService.legacyCuts(parts, List.of(new SponsorBlockService.Segment(7, 21)));

        assertEquals("2-3", cuts);
        assertArrayEquals(new boolean[]{false, false, true, true, false, false}, toArray(PlaybackService.dropped(cuts, 6)));
        assertNull(PlaybackService.legacyCuts(parts, List.of(new SponsorBlockService.Segment(6, 9))));
        assertEquals("0-0_3-5", PlaybackService.legacyCuts(parts,
                List.of(new SponsorBlockService.Segment(0, 5), new SponsorBlockService.Segment(14.98, 30))));
    }

    private static boolean[] toArray(boolean[] value) { return value; }
}
