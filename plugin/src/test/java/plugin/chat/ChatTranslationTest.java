package plugin.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import arc.Core;
import arc.mock.MockApplication;
import common.translation.TranslationResponse;
import mindustry.gen.Player;
import mindustry.net.NetConnection;
import plugin.gateway.ApiGateway;

class ChatTranslationTest {

    @BeforeEach
    void setUp() {
        Core.app = new MockApplication() {
            @Override
            public void post(Runnable runnable) {
                runnable.run();
            }
        };
    }

    @AfterEach
    void tearDown() {
        Core.app = null;
    }

    @Test
    void formatMessageWithoutSenderName() {
        String formatted = ChatTranslation.formatMessage("hello", "hello", "es",
                new TranslationResult("hola", "en"));
        assertEquals("hello [#00d47e](hola)", formatted);
    }

    @Test
    void formatMessageWithSenderName() {
        String formatted = ChatTranslation.formatMessage("Alice", "hello", "hello", "es",
                new TranslationResult("hola", "en"));
        assertEquals("Alice[white]: hello [#00d47e](hola)", formatted);
    }

    @Test
    void formatMessageFallsBackWhenResultNull() {
        String formatted = ChatTranslation.formatMessage("Alice", "hello", "hello", "es", null);
        assertEquals("Alice[white]: hello", formatted);
    }

    @Test
    void formatMessageFallsBackWhenTranslatedTextBlank() {
        String formatted = ChatTranslation.formatMessage("Alice", "hello", "hello", "es",
                new TranslationResult("   ", "en"));
        assertEquals("Alice[white]: hello", formatted);
    }

    @Test
    void formatMessageOmitsBracketWhenRecipientLangMatchesSourceLang() {
        String formatted = ChatTranslation.formatMessage("Alice", "hello", "hello", "en",
                new TranslationResult("hello", "en"));
        assertEquals("Alice[white]: hello", formatted);
    }

    @Test
    void formatMessageOmitsBracketWhenTranslatedTextEqualsCleanText() {
        String formatted = ChatTranslation.formatMessage("Alice", "hello", "hello", "es",
                new TranslationResult("hello", "en"));
        assertEquals("Alice[white]: hello", formatted);
    }

    @Test
    void dispatchToGroupDeliversFormattedMessage() {
        List<String> received = new ArrayList<>();
        Player recipient = new Player() {
            @Override
            public boolean isAdded() {
                return true;
            }

            @Override
            public void sendMessage(String text, Player from, String unformatted) {
                received.add(text);
            }
        };
        recipient.con = new NetConnection("127.0.0.1") {
            @Override
            public void send(Object object, boolean reliable) {
            }

            @Override
            public void close() {
            }
        };

        ChatTranslation chatTranslation = new ChatTranslation(null);
        Player sender = Player.create();
        sender.name = "Alice";

        chatTranslation.dispatchToGroup(sender, "hello", "hello", "es",
                new TranslationResult("hola", "en"), List.of(recipient));

        assertEquals(1, received.size());
        assertEquals("Alice[white]: hello [#00d47e](hola)", received.get(0));
    }

    @Test
    void dispatchToGroupSkipsDisconnectedPlayer() {
        List<String> received = new ArrayList<>();
        Player recipient = new Player() {
            @Override
            public boolean isAdded() {
                return false;
            }

            @Override
            public void sendMessage(String text, Player from, String unformatted) {
                received.add(text);
            }
        };

        ChatTranslation chatTranslation = new ChatTranslation(null);
        Player sender = Player.create();
        sender.name = "Alice";

        chatTranslation.dispatchToGroup(sender, "hello", "hello", "es",
                new TranslationResult("hola", "en"), List.of(recipient));

        assertTrue(received.isEmpty());
    }

    @Test
    void handleAsyncTranslationDeliversOnSuccess() {
        CompletableFuture<TranslationResponse> future = new CompletableFuture<>();
        ApiGateway stubGateway = new ApiGateway(null, null) {
            @Override
            @SuppressWarnings("unchecked")
            public <R> CompletableFuture<R> sendRequest(String type, Object payload, Class<R> clazz) {
                return (CompletableFuture<R>) future;
            }
        };

        ChatTranslation chatTranslation = new ChatTranslation(stubGateway);
        List<String> received = new ArrayList<>();
        Player recipient = new Player() {
            @Override
            public boolean isAdded() {
                return true;
            }

            @Override
            public void sendMessage(String text, Player from, String unformatted) {
                received.add(text);
            }
        };
        recipient.con = new NetConnection("127.0.0.1") {
            @Override
            public void send(Object object, boolean reliable) {
            }

            @Override
            public void close() {
            }
        };

        Player sender = Player.create();
        sender.name = "Bob";

        chatTranslation.handleAsyncTranslation(sender, "good game", "good game", "es", List.of(recipient));

        assertTrue(received.isEmpty(), "Should not deliver before translation future completes");

        TranslationResponse response = new TranslationResponse();
        response.setTranslatedText("buen juego");
        response.setSourceLanguage("en");
        future.complete(response);

        assertEquals(1, received.size());
        assertEquals("Bob[white]: good game [#00d47e](buen juego)", received.get(0));
    }

    @Test
    void handleAsyncTranslationFallsBackOnError() {
        CompletableFuture<TranslationResponse> future = new CompletableFuture<>();
        ApiGateway stubGateway = new ApiGateway(null, null) {
            @Override
            @SuppressWarnings("unchecked")
            public <R> CompletableFuture<R> sendRequest(String type, Object payload, Class<R> clazz) {
                return (CompletableFuture<R>) future;
            }
        };

        ChatTranslation chatTranslation = new ChatTranslation(stubGateway);
        List<String> received = new ArrayList<>();
        Player recipient = new Player() {
            @Override
            public boolean isAdded() {
                return true;
            }

            @Override
            public void sendMessage(String text, Player from, String unformatted) {
                received.add(text);
            }
        };
        recipient.con = new NetConnection("127.0.0.1") {
            @Override
            public void send(Object object, boolean reliable) {
            }

            @Override
            public void close() {
            }
        };

        Player sender = Player.create();
        sender.name = "Bob";

        chatTranslation.handleAsyncTranslation(sender, "good game", "good game", "es", List.of(recipient));

        future.completeExceptionally(new RuntimeException("Translation service down"));

        assertEquals(1, received.size());
        assertEquals("Bob[white]: good game", received.get(0));
    }
}
