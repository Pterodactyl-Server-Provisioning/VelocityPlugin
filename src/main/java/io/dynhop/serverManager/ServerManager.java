package io.dynhop.serverManager;

import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;

import javax.inject.Inject;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

@Plugin(
    id = "servermanager",
    name = "ServerManager",
    version = "1.0"
    ,description = "Manage The Velocity Server"
    ,url = "dynhop.io"
    ,authors = {"Zero MØtion"}
)
public class ServerManager {

    private final ProxyServer server;
    private final Logger logger;
    private HttpServer httpServer;
    private final java.util.Set<String> fallbackServers = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    // Hardcoded bearer token; API port is computed from env SERVER_PORT + 1 (Pterodactyl)
    private static final String BEARER_TOKEN = "rpUV9REOurOWCXf2YcGQsRITFxiF3tiDM0YzA95y5p6X14VEQp7Ww3g5tIYktGy7";

    @Inject
    public ServerManager(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
        // Register this class as an event listenen
        // Defer API startup until proxy initialization event to ensure registry is ready.
        startApiServer();
    }

    private void startApiServer() {
        int apiPort = computeApiPort();
        try {
            httpServer = HttpServer.create(new InetSocketAddress(apiPort), 0);
            httpServer.createContext("/api/register", new AuthHandler(this::handleRegister));
            httpServer.createContext("/api/register-fallback", new AuthHandler(this::handleRegisterFallback));
            httpServer.createContext("/api/unregister", new AuthHandler(this::handleUnregister));
            httpServer.createContext("/api/servers", new AuthHandler(this::handleListServers));
            httpServer.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            httpServer.start();
            logger.info("ServerManager API listening on :{}", apiPort);
        } catch (IOException e) {
            logger.error("Failed to start API server on port {}: {}", apiPort, e.getMessage(), e);
        }
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        // On startup, attempt to unregister the typical default servers if they exist
        unregisterDefaultServers();
    }

    private void unregisterDefaultServers() {
        // name -> port mapping for 127.0.0.1
        tryUnregisterIfMatches("lobby", "127.0.0.1", 30066);
        tryUnregisterIfMatches("minigames", "127.0.0.1", 30068);
        tryUnregisterIfMatches("factions", "127.0.0.1", 30067);
    }

    private void tryUnregisterIfMatches(String name, String host, int port) {
        Optional<RegisteredServer> rsOpt = server.getServer(name);
        if (rsOpt.isEmpty()) {
            return;
        }
        RegisteredServer rs = rsOpt.get();
        InetSocketAddress addr = rs.getServerInfo().getAddress();
        String actualHost = addr.getHostString();
        int actualPort = addr.getPort();
        if (actualPort == port && (actualHost.equals(host) || actualHost.equals("localhost"))) {
            server.unregisterServer(rs.getServerInfo());
            fallbackServers.remove(name);
            System.out.println("Startup cleanup: unregistered default server '" + name + "' -> " + actualHost + ":" + actualPort);
        } else {
            System.out.println("Startup cleanup: keeping server '" + name + "' -> " + actualHost + ":" + actualPort + " (does not match configured default)");
        }
    }

    // Auth wrapper: checks Authorization: Bearer <token>
    private class AuthHandler implements HttpHandler {
        private final HttpHandler next;
        AuthHandler(HttpHandler next) { this.next = next; }
        @Override public void handle(HttpExchange exchange) throws IOException {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.equals("Bearer " + BEARER_TOKEN)) {
                send(exchange, 401, "Unauthorized");
                return;
            }
            next.handle(exchange);
        }
    }

    // Compute API port as SERVER_PORT + 1; fall back to 8081 if env missing/invalid
    private int computeApiPort() {
        String env = System.getenv("SERVER_PORT");
        int basePort = 0;
        if (env != null) {
            try {
                basePort = Integer.parseInt(env.trim());
            } catch (NumberFormatException ignored) {
                logger.warn("SERVER_PORT is not a valid integer: '{}'. Falling back to 8080+1", env);
            }
        } else {
            logger.warn("SERVER_PORT env not set. Falling back to 8080+1");
        }
        int computed = (basePort > 0 ? basePort : 8080) + 1;
        return computed;
    }

    private void handleRegister(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "Method Not Allowed");
            return;
        }
        Map<String, String> params = queryParams(exchange.getRequestURI());
        String name = params.get("name");
        String host = params.get("host");
        String portStr = params.get("port");
        if (name == null || host == null || portStr == null) {
            send(exchange, 400, "Missing parameters: name, host, port");
            return;
        }
        int port;
        try { port = Integer.parseInt(portStr); } catch (NumberFormatException e) { send(exchange, 400, "Invalid port"); return; }

        boolean fallback = false;
        String fallbackParam = params.get("fallback");
        if (fallbackParam != null) {
            String fp = fallbackParam.trim().toLowerCase();
            fallback = fp.equals("true") || fp.equals("1") || fp.equals("yes");
        }
        String result = registerServer(name, host, port, fallback);
        send(exchange, 200, result);
    }

    private void handleRegisterFallback(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "Method Not Allowed");
            return;
        }
        Map<String, String> params = queryParams(exchange.getRequestURI());
        String name = params.get("name");
        String host = params.get("host");
        String portStr = params.get("port");
        if (name == null || host == null || portStr == null) {
            send(exchange, 400, "Missing parameters: name, host, port");
            return;
        }
        int port;
        try { port = Integer.parseInt(portStr); } catch (NumberFormatException e) { send(exchange, 400, "Invalid port"); return; }

        String result = registerServer(name, host, port, true);
        send(exchange, 200, result);
    }

    private void handleUnregister(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "Method Not Allowed");
            return;
        }
        Map<String, String> params = queryParams(exchange.getRequestURI());
        String name = params.get("name");
        if (name == null) { send(exchange, 400, "Missing parameter: name"); return; }
        String result = removeServer(name);
        send(exchange, 200, result);
    }

    private void handleListServers(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "Method Not Allowed");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Registered servers:\n");
        for (RegisteredServer rs : server.getAllServers()) {
            InetSocketAddress addr = rs.getServerInfo().getAddress();
            sb.append("- ").append(rs.getServerInfo().getName())
                    .append(" -> ")
                    .append(addr.getHostString()).append(":").append(addr.getPort());
            if (fallbackServers.contains(rs.getServerInfo().getName())) sb.append(" [fallback]");
            sb.append('\n');
        }
        send(exchange, 200, sb.toString());
    }

    private static Map<String, String> queryParams(URI uri) {
        java.util.HashMap<String, String> map = new java.util.HashMap<>();
        String q = uri.getQuery();
        if (q == null || q.isEmpty()) return map;
        for (String pair : q.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String k = java.net.URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String v = java.net.URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                map.put(k, v);
            }
        }
        return map;
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    public String registerServer(String serverName, String address, int port) {
        return registerServer(serverName, address, port, false);
    }

    public String registerServer(String serverName, String address, int port, boolean fallback) {
        ServerInfo serverInfo = new ServerInfo(serverName, new InetSocketAddress(address, port));
        Optional<RegisteredServer> existing = server.getServer(serverName);
        if (existing.isPresent()) {
            return "Server already registered: " + serverName;
        }
        server.registerServer(serverInfo);
        if (fallback) {
            fallbackServers.add(serverName);
        }
        String msg = "Registered server: " + serverName + " -> " + address + ":" + port + (fallback ? " [fallback]" : "");
        logger.info(msg);
        return msg;
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        // Try to redirect the player to a registered fallback server if available
        for (String name : fallbackServers) {
            Optional<RegisteredServer> rs = server.getServer(name);
            if (rs.isPresent()) {
                // Avoid redirect loop: don't redirect to the same server they're kicked from
                if (!event.getServer().getServerInfo().getName().equalsIgnoreCase(name)) {
                    event.setResult(KickedFromServerEvent.RedirectPlayer.create(rs.get()));
                    logger.info("Redirecting player '{}' to fallback server '{}' after kick.", event.getPlayer().getUsername(), name);
                    return;
                }
            }
        }
        // If no fallback found, let Velocity handle default behavior
    }

    @Subscribe
    public void onPlayerChooseInitialServer(PlayerChooseInitialServerEvent event) {
        // If Velocity already chose a server (e.g., from config), keep it.
        if (event.getInitialServer().isPresent()) {
            return;
        }
        // Prefer a registered fallback server, if available
        for (String name : fallbackServers) {
            Optional<RegisteredServer> rs = server.getServer(name);
            if (rs.isPresent()) {
                event.setInitialServer(rs.get());
                System.out.println("Initial server for '" + event.getPlayer().getUsername() + "' set to fallback '" + name + "'.");
                return;
            }
        }
        // Otherwise, choose any available registered server
        Optional<RegisteredServer> any = server.getAllServers().stream().findFirst();
        any.ifPresent(rs -> {
            event.setInitialServer(rs);
            System.out.println("Initial server for '" + event.getPlayer().getUsername() + "' set to '" + rs.getServerInfo().getName() + "'.");
        });
    }

    public String removeServer(String serverName) {
        Optional<RegisteredServer> registered = server.getServer(serverName);
        if (registered.isPresent()) {
            server.unregisterServer(registered.get().getServerInfo());
            fallbackServers.remove(serverName);
            System.out.println("Removed server: " + serverName);
            return "Removed server: " + serverName;
        }
        return "Server not found: " + serverName;
    }
}
