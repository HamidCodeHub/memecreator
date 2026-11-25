package com.memecreator.harvest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memecreator.config.MemeProperties;
import com.memecreator.harvest.CatalogEntry.Status;
import com.memecreator.template.LayoutRepository;
import com.memecreator.template.MemeTemplate;
import com.memecreator.template.TemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.util.unit.DataSize;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.memecreator.harvest.ImageFingerprintTest.art;
import static com.memecreator.harvest.ImageFingerprintTest.encode;
import static com.memecreator.harvest.ImageFingerprintTest.scaled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateHarvesterTest {

    @TempDir
    Path tmp;

    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final Map<String, byte[]> images = new HashMap<>();
    final List<String> downloads = new ArrayList<>();
    final List<TemplateCandidate> imgflip = new ArrayList<>();
    final List<TemplateCandidate> memegen = new ArrayList<>();

    Path local;
    Path storage;
    HarvestProperties props;
    MemeProperties memeProps;
    TemplateCatalog catalog;
    TemplateHarvester harvester;

    @BeforeEach
    void setUp() throws IOException {
        local = Files.createDirectories(tmp.resolve("meme"));
        storage = tmp.resolve("meme-storage");
        props = new HarvestProperties(true, storage.toString(), true, false, "0 15 4 * * *", "0 45 4 * * MON",
                "https://example.test/imgflip", "https://example.test/memegen", 10, 250, 1.5,
                DataSize.ofMegabytes(15), Duration.ZERO, Duration.ofSeconds(5), Duration.ofSeconds(5), "test");
        memeProps = new MemeProperties(local.toString(), tmp.resolve("out").toString(), 4, "English",
                List.of("Arial"), 0.92f, "classpath:template-layouts.yml", tmp.resolve("private").toString(),
                Duration.ofDays(90));
        HarvestHttp http = new HarvestHttp(props, mapper) {
            @Override
            public byte[] getBytes(String url) {
                downloads.add(url);
                byte[] b = images.get(url);
                if (b == null) {
                    throw new UncheckedIOException(new IOException("HTTP 404"));
                }
                return b;
            }
        };
        catalog = new TemplateCatalog(props, mapper);
        harvester = new TemplateHarvester(List.of(source("imgflip", true, imgflip), source("memegen", false, memegen)),
                catalog, http, props, new LayoutRepository(memeProps, new DefaultResourceLoader()), memeProps);
    }

    static TemplateSource source(String name, boolean ranked, List<TemplateCandidate> list) {
        return new TemplateSource() {
            public String name() {
                return name;
            }

            public boolean ranked() {
                return ranked;
            }

            public List<TemplateCandidate> fetch() {
                return list;
            }
        };
    }

    void offer(List<TemplateCandidate> list, String source, String id, String name, int boxes, BufferedImage img,
               String format) throws IOException {
        String url = "https://example.test/" + source + "/" + id + "." + format;
        images.put(url, encode(img, format));
        list.add(new TemplateCandidate(source, id, name, url, null, boxes, list.size() + 1, 0));
    }

    @Test
    void downloadsNewTemplatesAndSkipsDuplicates() throws IOException {
        BufferedImage drake = art(1, 600, 600);
        BufferedImage bee = art(2, 500, 400);
        Files.write(local.resolve("181913649_Drake Hotline Bling.jpg"), encode(drake, "jpg"));

        offer(imgflip, "imgflip", "181913649", "Drake Hotline Bling", 2, drake, "jpg");  // id known from file name
        offer(imgflip, "imgflip", "111", "Bee Movie: Ya Like Jazz?", 2, bee, "jpg");      // new
        offer(imgflip, "imgflip", "222", "Drake Again", 2, scaled(drake, 900, 900), "png"); // same picture as local
        offer(imgflip, "imgflip", "333", "Tiny", 2, art(3, 120, 100), "jpg");              // too small
        offer(imgflip, "imgflip", "444", "Some Four Panel", 4, art(4, 700, 500), "jpg");   // no layout for 4 boxes
        offer(memegen, "memegen", "bee", "Bee (memegen)", 2, scaled(bee, 1000, 800), "jpg"); // sharper Bee

        List<HarvestReport> reports = harvester.run("all");
        HarvestReport i = reports.get(0);
        HarvestReport m = reports.get(1);

        assertFalse(downloads.contains(imgflip.get(0).imageUrl()));
        assertEquals(1, i.known());
        assertEquals(1, i.added());
        assertEquals(1, i.duplicates());
        assertEquals(1, i.rejected());
        assertEquals(1, i.needsLayout());
        assertEquals(1, m.duplicates());

        Path templates = storage.resolve("templates");
        assertTrue(Files.exists(templates.resolve("111_Bee Movie Ya Like Jazz.jpg")));
        assertTrue(Files.exists(storage.resolve("needs-layout/444_Some Four Panel.jpg")));

        catalog.load();
        CatalogEntry beeEntry = catalog.byKey("imgflip:111").orElseThrow();
        assertSame(beeEntry, catalog.byKey("memegen:bee").orElseThrow());
        assertEquals(1000, beeEntry.width); // replaced by the sharper copy

        List<String> names = new TemplateRepository(memeProps, props).findAll().stream().map(MemeTemplate::name).toList();
        assertTrue(names.contains("Bee Movie Ya Like Jazz"));
        assertTrue(names.contains("Drake Hotline Bling"));
        assertFalse(names.contains("Some Four Panel"));

        downloads.clear();
        harvester.run("all");
        assertTrue(downloads.isEmpty(), "second run downloads nothing: " + downloads);
    }

    @Test
    void deletedTemplateAndItsLookAlikesStayGone() throws IOException {
        BufferedImage img = art(5, 640, 480);
        offer(imgflip, "imgflip", "555", "Gone Soon", 2, img, "jpg");
        harvester.run("imgflip");
        Files.delete(storage.resolve("templates/555_Gone Soon.jpg"));

        offer(imgflip, "imgflip", "556", "Gone Soon Reupload", 2, scaled(img, 800, 600), "jpg");
        HarvestReport r = harvester.run("imgflip").get(0);

        catalog.load();
        assertEquals(Status.REMOVED, catalog.byKey("imgflip:555").orElseThrow().status);
        assertEquals(1, r.duplicates());
        assertFalse(Files.exists(storage.resolve("templates/556_Gone Soon Reupload.jpg")));
    }

    @Test
    void failedDownloadsAreRetriedNextRun() {
        imgflip.add(new TemplateCandidate("imgflip", "777", "Offline", "https://example.test/404.jpg", null, 2, 1, 0));
        assertEquals(1, harvester.run("imgflip").get(0).failed());
        downloads.clear();
        harvester.run("imgflip");
        assertEquals(List.of("https://example.test/404.jpg"), downloads);
    }

    @Test
    void parsesImgflipAndMemegenLists() throws IOException {
        JsonNode imgflipJson = mapper.readTree("""
                {"success": true, "data": {"memes": [
                  {"id": "181913649", "name": "Drake Hotline Bling", "url": "https://i.imgflip.com/30b1gx.jpg",
                   "width": 1200, "height": 1200, "box_count": 2, "captions": 1234}]}}""");
        JsonNode memegenJson = mapper.readTree("""
                [{"id": "ds", "name": "Daily Struggle", "lines": 3, "blank": "https://api.memegen.link/images/ds.png",
                  "source": "https://knowyourmeme.com/memes/daily-struggle"}]""");
        HarvestHttp http = new HarvestHttp(props, mapper) {
            @Override
            public JsonNode getJson(String url) {
                return url.contains("imgflip") ? imgflipJson : memegenJson;
            }
        };
        TemplateCandidate d = new ImgflipSource(http, props).fetch().get(0);
        assertEquals("imgflip:181913649", d.key());
        assertEquals(1, d.rank());
        assertEquals(1234, d.popularity());
        TemplateCandidate ds = new MemegenSource(http, props).fetch().get(0);
        assertEquals("memegen:ds", ds.key());
        assertEquals(3, ds.boxCount());
        assertEquals("https://api.memegen.link/images/ds.png", ds.imageUrl());
    }

    @Test
    void fileNamesAreSafeOnWindows() {
        assertEquals("12_What Is This.jpg",
                TemplateHarvester.fileName("12", "What: Is <This>?", "jpg"));
        assertEquals("12_template.png", TemplateHarvester.fileName("12", "???", "png"));
    }
}
