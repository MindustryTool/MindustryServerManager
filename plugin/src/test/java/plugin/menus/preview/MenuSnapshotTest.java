package plugin.menus.preview;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import arc.files.Fi;
import arc.struct.Seq;
import arc.struct.StringMap;
import mindustry.gen.Player;
import mindustry.maps.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.maprating.MapRatingRepository;
import plugin.maprating.MapRatingService;
import plugin.maprating.MapRatingStat;
import plugin.session.Session;
import plugin.vote.RtvMenu;
import plugin.vote.RtvService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuSnapshotTest {

    private Map createMap(String filename, String name) {
        StringMap tags = new StringMap();
        tags.put("name", name);
        return new Map(new Fi(filename), 100, 100, tags, true, 0, 146);
    }

    private static class StubRtvService extends RtvService {
        private final Seq<Map> maps;

        StubRtvService(Seq<Map> maps) {
            super(null, null);
            this.maps = maps;
        }

        @Override
        public Seq<Map> getMaps() {
            return maps;
        }

        @Override
        public boolean isVoted(Player player, String mapId) {
            return false;
        }
    }

    private static class StubMapRatingService extends MapRatingService {
        StubMapRatingService() {
            super((MapRatingRepository) null);
        }

        @Override
        public MapRatingStat getStat(Map map) {
            return new MapRatingStat(25, 4.85f);
        }
    }

    @Test
    void captureRtvMenuTightCropWritesValidPng(@TempDir Path tempDir) throws Exception {
        Map mapA = createMap("craters.msav", "Ancient Craters");
        Map mapB = createMap("ruins.msav", "Desolate Ruins");
        Seq<Map> maps = Seq.with(mapA, mapB);

        RtvMenu menu = new RtvMenu(new StubRtvService(maps), new StubMapRatingService());
        Session session = MockSession.builder().locale("en").name("DevTester").build();

        Path output = tempDir.resolve("rtv_tight.png");
        MenuSnapshot.builder()
                .menu(menu)
                .session(session)
                .fullScreen(false)
                .capture(output);

        assertTrue(Files.exists(output), "Output file must exist");
        assertTrue(Files.size(output) > 1000, "Output file must contain PNG data");

        BufferedImage image = ImageIO.read(output.toFile());
        assertNotNull(image, "Must be valid decodable image");
        assertTrue(image.getWidth() > 200, "Cropped width must enclose dialog");
        assertTrue(image.getHeight() > 150, "Cropped height must enclose dialog");
        assertTrue(image.getWidth() < 1920, "Tight crop width should be smaller than full 1080p canvas");
    }

    @Test
    void captureRtvMenuFullScreenWritesFullCanvas(@TempDir Path tempDir) throws Exception {
        Map map = createMap("craters.msav", "Ancient Craters");
        Seq<Map> maps = Seq.with(map);

        RtvMenu menu = new RtvMenu(new StubRtvService(maps), new StubMapRatingService());
        Session session = MockSession.builder().locale("vi").build();

        Path output = tempDir.resolve("rtv_fullscreen.png");
        MenuSnapshot.builder()
                .menu(menu)
                .session(session)
                .fullScreen(true)
                .capture(output);

        assertTrue(Files.exists(output), "Output file must exist");
        BufferedImage image = ImageIO.read(output.toFile());
        assertNotNull(image);
        assertEquals(1920, image.getWidth());
        assertEquals(1080, image.getHeight());
    }
}
