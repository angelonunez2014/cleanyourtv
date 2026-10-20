package com.gathin.tvhome;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SlideshowTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File file(String name, int bytes) throws IOException {
        File f = new File(tmp.getRoot(), name);
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(new byte[bytes]); }
        return f;
    }

    @Test public void onlyNonEmptyPhotosAndVideosAreListed() throws IOException {
        file("a.jpg", 10);
        file("B.JPEG", 10);
        file("c.png", 10);
        file("d.webp", 10);
        file("loop.MP4", 10);
        file("e.webm", 10);
        file("notes.txt", 10);
        file(".hidden.jpg", 10);
        file("empty.jpg", 0);
        new File(tmp.getRoot(), "sub.jpg").mkdir();
        List<String> names = new ArrayList<>();
        for (File f : Slideshow.media(tmp.getRoot())) names.add(f.getName());
        Collections.sort(names);
        assertEquals(List.of("B.JPEG", "a.jpg", "c.png", "d.webp", "e.webm", "loop.MP4"), names);
    }

    @Test public void videosAreToldApartFromPhotos() {
        assertTrue(Slideshow.isVideo(new File("x.mp4")));
        assertTrue(Slideshow.isVideo(new File("X.MKV")));
        assertFalse(Slideshow.isVideo(new File("x.jpg")));
        assertFalse(Slideshow.isVideo(new File("mp4.png")));
    }

    @Test public void missingFolderMeansNoPhotos() {
        assertTrue(Slideshow.media(new File(tmp.getRoot(), "nope")).isEmpty());
        assertTrue(Slideshow.media(null).isEmpty());
    }
}
