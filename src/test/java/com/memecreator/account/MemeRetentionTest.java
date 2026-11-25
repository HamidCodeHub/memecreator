package com.memecreator.account;

import com.memecreator.config.MemeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class MemeRetentionTest {

    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private static final Instant OLD = NOW.minus(Duration.ofHours(2));
    private static final Instant FRESH = NOW.minus(Duration.ofMinutes(10));

    @TempDir
    Path out;

    private final MemeSetRepository memeSets = mock(MemeSetRepository.class);
    private final FavoriteRepository favorites = mock(FavoriteRepository.class);
    private final SharedMemeRepository shares = mock(SharedMemeRepository.class);
    private final MemeRecordRepository records = mock(MemeRecordRepository.class);
    private MemeRetention retention;

    @BeforeEach
    void setUp() {
        MemeProperties props = new MemeProperties("templates", out.toString(), 4, "English", List.of("Arial"), 0.92f,
                "classpath:template-layouts.yml", out.resolve("private").toString(), Duration.ofDays(90));
        retention = new MemeRetention(memeSets, favorites, shares, records,
                new RetentionProperties(true, Duration.ofHours(1)), props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Path set(String folder, Instant made, String... files) throws IOException {
        Path dir = Files.createDirectories(out.resolve(folder));
        for (String f : files) {
            file(dir, f, made);
        }
        when(memeSets.findByFolder(folder)).thenReturn(
                List.of(new MemeSet(7L, folder, "topic", MemeSet.Source.WEB, made)));
        return dir;
    }

    private static Path file(Path dir, String name, Instant modified) throws IOException {
        Path p = Files.writeString(dir.resolve(name), "x");
        Files.setLastModifiedTime(p, FileTime.from(modified));
        return p;
    }

    private static Favorite favorite(String folder, String file) {
        return new Favorite(7L, new MemeLibrary.MemeRef(folder, file), "Your photo", null, OLD);
    }

    private static MemeRecord record(String folder, String file, String aiFile) {
        return new MemeRecord(7L, folder, file, MemeRecord.Kind.PHOTO, null, "TOP", "[]", "[]", aiFile, OLD);
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void keepsFavoritesSharedMemesAndTheUploadOfAFavoritedPhotoSet() throws IOException {
        String folder = "20261125-100000_photo";
        Path dir = set(folder, OLD, "00_upload.jpg", "00_keep-clear.json", "01_top.jpg", "02_bottom.jpg",
                "03_caption-bar.jpg", "04_top.jpg");
        file(dir, "03_caption-bar-edit1.jpg", FRESH);   // an edit made 10 minutes ago
        when(favorites.findByFolder(folder)).thenReturn(List.of(favorite(folder, "01_top.jpg")));
        when(shares.findByFolder(folder)).thenReturn(List.of(new SharedMeme("tok", 7L,
                new MemeLibrary.MemeRef(folder, "02_bottom.jpg"), "Your photo", null, OLD)));
        MemeRecord r3 = record(folder, "03_caption-bar.jpg", "03_caption-bar.jpg");
        MemeRecord r4 = record(folder, "04_top.jpg", "04_top.jpg");
        when(records.findByFolder(folder)).thenReturn(List.of(record(folder, "01_top.jpg", "01_top.jpg"), r3, r4));

        assertThat(retention.deleteExpired()).isEqualTo(2);

        assertThat(names(dir)).containsExactly("00_keep-clear.json", "00_upload.jpg", "01_top.jpg", "02_bottom.jpg",
                "03_caption-bar-edit1.jpg");
        verify(records).deleteAll(List.of(r3, r4));
    }

    @Test
    void theUploadGoesWhenNothingIsAFavoriteEvenIfAMemeIsShared() throws IOException {
        String folder = "20261125-100000_photo_mondays";
        Path dir = set(folder, OLD, "00_upload.jpg", "00_keep-clear.json", "01_top.jpg", "02_bottom.jpg");
        when(shares.findByFolder(folder)).thenReturn(List.of(new SharedMeme("tok", 7L,
                new MemeLibrary.MemeRef(folder, "02_bottom.jpg"), "Your photo", null, OLD)));

        retention.deleteExpired();

        assertThat(names(dir)).containsExactly("02_bottom.jpg");
    }

    @Test
    void anEditedFavoriteKeepsTheAiOriginal() throws IOException {
        String folder = "20261125-100000_cats";
        Path dir = set(folder, OLD, "01_drake.jpg", "01_drake-edit1.jpg", "02_doge.jpg");
        when(favorites.findByFolder(folder)).thenReturn(List.of(favorite(folder, "01_drake-edit1.jpg")));
        when(records.findByFolder(folder)).thenReturn(List.of(record(folder, "01_drake-edit1.jpg", "01_drake.jpg")));

        retention.deleteExpired();

        assertThat(names(dir)).containsExactly("01_drake-edit1.jpg", "01_drake.jpg");
    }

    @Test
    void aSetNobodyKeptDisappearsWithItsFolder() throws IOException {
        set("20261125-100000_mondays", OLD, "01_drake.jpg", "02_doge.jpg");

        assertThat(retention.deleteExpired()).isEqualTo(2);
        assertThat(out.resolve("20261125-100000_mondays")).doesNotExist();
    }

    @Test
    void freshSetsAndFoldersThatArentAnAccountsSetAreLeftAlone() throws IOException {
        Path fresh = set("20261125-115500_mondays", FRESH, "01_drake.jpg");
        Path face = Files.createDirectories(out.resolve("face-1234"));
        file(face, "face.jpg", OLD);   // no meme_set row: the face feature's own folder

        assertThat(retention.deleteExpired()).isZero();
        assertThat(names(fresh)).containsExactly("01_drake.jpg");
        assertThat(names(face)).containsExactly("face.jpg");
        verify(records, never()).deleteAll(any());
    }
}
