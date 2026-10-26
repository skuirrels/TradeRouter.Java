package io.github.skuirrels.traderouter;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A loopback HTTP server for road provider tests. It records the last request and answers with one canned
 * response, never answers, or sends the response headers and then stalls before the body.
 */
final class StubHttpServer implements AutoCloseable {
    private enum Mode { RESPOND, HANG, STALL_BODY }

    final AtomicReference<String> method = new AtomicReference<>();
    final AtomicReference<URI> requestUri = new AtomicReference<>();
    final AtomicReference<Headers> headers = new AtomicReference<>();
    final AtomicReference<String> body = new AtomicReference<>();

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "stub-http-server");
        thread.setDaemon(true);
        return thread;
    });
    private final CountDownLatch released = new CountDownLatch(1);

    private StubHttpServer(int status, String response, Mode mode) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            requestUri.set(exchange.getRequestURI());
            headers.set(exchange.getRequestHeaders());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (mode == Mode.HANG) {
                awaitRelease();
                exchange.close();
                return;
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, mode == Mode.STALL_BODY ? bytes.length + 1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
                if (mode == Mode.STALL_BODY) {
                    out.flush();
                    awaitRelease();
                }
            } catch (IOException ex) {
                // The client gave up on the stalled body.
            }
        });
        server.start();
    }

    /** Answers every request with the given status and body. */
    static StubHttpServer responding(int status, String response) throws IOException {
        return new StubHttpServer(status, response, Mode.RESPOND);
    }

    /** Accepts requests but never answers them. */
    static StubHttpServer hanging() throws IOException {
        return new StubHttpServer(0, "", Mode.HANG);
    }

    /** Sends 200 headers and part of the body, then stalls until closed. */
    static StubHttpServer stallingBody(String partialBody) throws IOException {
        return new StubHttpServer(200, partialBody, Mode.STALL_BODY);
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** The first value of a header on the last request, or null. */
    String header(String name) {
        Headers recorded = headers.get();
        return recorded == null ? null : recorded.getFirst(name);
    }

    private void awaitRelease() {
        try {
            released.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        released.countDown();
        server.stop(0);
        executor.shutdownNow();
    }
}
