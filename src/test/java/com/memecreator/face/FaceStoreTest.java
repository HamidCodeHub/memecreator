package com.memecreator.face;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.memecreator.config.MemeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FaceStoreTest {

    @TempDir
    Path tmp;

    private FaceStore store() {
        MemeProperties props = new MemeProperties("templates", tmp.resolve("output").toString(), 4, "English",
                List.of("Impact"), 0.9f, "classpath:template-layouts.yml", tmp.resolve("private").toString(),
                Duration.ofDays(90));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new FaceStore(props, mapper);
    }

    @Test
    void savesReadsAndDeletesFaceWithItsMemes() throws Exception {
        FaceStore store = store();
        var rec = store.save(new byte[]{1, 2, 3}, true, true);
        Path memeDir = Files.createDirectories(tmp.resolve("output/face-abc"));
        Files.write(memeDir.resolve("01.jpg"), new byte[]{9});
        store.addMemeFolder(rec.id(), "face-abc");

        assertThat(store.photo(rec.id())).contains(new byte[]{1, 2, 3});
        assertThat(store.record(rec.id()).orElseThrow().memeFolders()).containsExactly("face-abc");
        assertThat(store.record(rec.id()).orElseThrow().confirmedAdult()).isTrue();

        assertThat(store.delete(rec.id())).isTrue();
        assertThat(store.photo(rec.id())).isEmpty();
        assertThat(memeDir).doesNotExist();
        assertThat(store.delete(rec.id())).isFalse();
    }

    @Test
    void rejectsIdsThatAreNotUuids() {
        FaceStore store = store();
        assertThatThrownBy(() -> store.photo("../../secrets")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void purgesFacesOlderThanTheRetention() throws Exception {
        FaceStore store = store();
        var oldFace = store.save(new byte[]{1}, true, true);
        var newFace = store.save(new byte[]{2}, true, true);
        Path meta = tmp.resolve("private/faces").resolve(oldFace.id()).resolve("meta.json");
        String json = Files.readString(meta).replace(oldFace.uploadedAt().toString(),
                Instant.now().minus(Duration.ofDays(91)).toString());
        Files.writeString(meta, json);

        store.purgeExpired();

        assertThat(store.photo(oldFace.id())).isEmpty();
        assertThat(store.photo(newFace.id())).isPresent();
    }
}
