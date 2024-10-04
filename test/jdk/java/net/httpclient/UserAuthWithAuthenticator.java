/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/**
 * @test
 * @bug 8326949
 * @run main/othervm UserAuthWithAuthenticator
 * @summary Authorization header is removed when a proxy Authenticator is set
 */

import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.*;
import java.util.*;

import static java.nio.charset.StandardCharsets.US_ASCII;

public class UserAuthWithAuthenticator {

    static final String data = "0123456789";

    static final String data1 = "ABCDEFGHIJKL";

    static final String[] proxyResponses = {
        "HTTP/1.1 407 Proxy Authentication Required\r\n"+
        "Proxy-Authenticate: Basic realm=\"Access to the proxy\"\r\n\r\n"
        ,
        "HTTP/1.1 200 OK\r\n"+
        "Date: Mon, 15 Jan 2001 12:18:21 GMT\r\n" +
        "Server: Apache/1.3.14 (Unix)\r\n" +
        "Content-Length: " + data.length() + "\r\n\r\n" + data
    };

    static final String[] serverResponses = {
        "HTTP/1.1 200 OK\r\n"+
        "Date: Mon, 15 Jan 2001 12:18:21 GMT\r\n" +
        "Server: Apache/1.3.14 (Unix)\r\n" +
        "Content-Length: " + data1.length() + "\r\n\r\n" + data1
    };

    static final String[] authenticatorResponses = {
        "HTTP/1.1 401 Authentication Required\r\n"+
        "WWW-Authenticate: Basic realm=\"Access to the server\"\r\n\r\n"
        ,
        "HTTP/1.1 200 OK\r\n"+
        "Date: Mon, 15 Jan 2001 12:18:21 GMT\r\n" +
        "Server: Apache/1.3.14 (Unix)\r\n" +
        "Content-Length: " + data1.length() + "\r\n\r\n" + data1
    };

    public static void main(String[] args) throws Exception {
        testServerOnly();
        testServerWithProxy();
        testServerOnlyAuthenticator();
    }

    static void testServerWithProxy() throws IOException, InterruptedException {
        Mocker proxyMock = new Mocker(proxyResponses);
        proxyMock.start();
        try {

            var client = HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .proxy(new ProxySel(proxyMock.getPort()))
                .authenticator(new ProxyAuth())
                .build();

            var plainCreds = "user:pwd";
            var encoded = java.util.Base64.getEncoder().encodeToString(plainCreds.getBytes(US_ASCII));
            var badCreds = "user:wrong";
            var encoded1 = java.util.Base64.getEncoder().encodeToString(badCreds.getBytes(US_ASCII));
            var request = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1/some_url"))
                .setHeader("User-Agent", "myUserAgent")
                .setHeader("Authorization", "Basic " + encoded)
                .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(data, response.body());
            var proxyStr = proxyMock.getRequest(1);

            assertContains(proxyStr, "/some_url");
            assertPattern(".*^Proxy-Authorization:.*Basic " + encoded + ".*", proxyStr);
            assertPattern(".*^User-Agent:.*myUserAgent.*", proxyStr);
            assertPattern(".*^Authorization:.*Basic.*", proxyStr);
            System.out.println("testServerWithProxy: OK");
        } finally {
            proxyMock.stopMocker();
        }
    }

    static void testServerOnly() throws IOException, InterruptedException {
        Mocker serverMock = new Mocker(serverResponses);
        serverMock.start();
        try {
            var client = HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();

            var plainCreds = "user:pwd";
            var encoded = java.util.Base64.getEncoder().encodeToString(plainCreds.getBytes(US_ASCII));
            var request = HttpRequest.newBuilder().uri(URI.create(serverMock.baseURL() + "/some_serv_url"))
                .setHeader("User-Agent", "myUserAgent")
                .setHeader("Authorization", "Basic " + encoded)
                .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(data1, response.body());

            var serverStr = serverMock.getRequest(0);
            assertContains(serverStr, "/some_serv_url");
            assertPattern(".*^User-Agent:.*myUserAgent.*", serverStr);
            assertPattern(".*^Authorization:.*Basic " + encoded + ".*", serverStr);
            System.out.println("testServerOnly: OK");
        } finally {
            serverMock.stopMocker();
        }
    }

    // This is effectively a regression test for existing behavior
    static void testServerOnlyAuthenticator() throws IOException, InterruptedException {
        Mocker serverMock = new Mocker(authenticatorResponses);
        serverMock.start();
        try {
            var client = HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .authenticator(new ServerAuth())
                .build();

            // credentials set in the server authenticator
            var plainCreds = "serverUser:serverPwd";
            var encoded = java.util.Base64.getEncoder().encodeToString(plainCreds.getBytes(US_ASCII));
            var request = HttpRequest.newBuilder().uri(URI.create(serverMock.baseURL() + "/some_serv_url"))
                .setHeader("User-Agent", "myUserAgent")
                .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(data1, response.body());

            var serverStr = serverMock.getRequest(1);
            assertContains(serverStr, "/some_serv_url");
            assertPattern(".*^User-Agent:.*myUserAgent.*", serverStr);
            assertPattern(".*^Authorization:.*Basic " + encoded + ".*", serverStr);
            System.out.println("testServerOnlyAuthenticator: OK");
        } finally {
            serverMock.stopMocker();
        }
    }

    static void close(Closeable... clarray) {
        for (Closeable c : clarray) {
            try {
                c.close();
            } catch (Exception e) {}
        }
    }

    static class Mocker extends Thread {
        final ServerSocket ss;
        final String[] responses;
        volatile List<String> requests;
        volatile InputStream in;
        volatile OutputStream out;
        volatile Socket s = null;

        public Mocker(String[] responses) throws IOException {
            this.ss = new ServerSocket(0);
            this.responses = responses;
            this.requests = new LinkedList<>();
        }

        public void stopMocker() {
            close(ss, s, in, out);
        }

        public int getPort() {
            return ss.getLocalPort();
        }

        public String baseURL() {
            return "http://127.0.0.1:" + getPort();
        }

        private String readRequest() throws IOException {
            String req = "";
            while (!req.endsWith("\r\n\r\n")) {
                int x = in.read();
                if (x == -1) {
                    s.close();
                    s = ss.accept();
                    in = s.getInputStream();
                    out = s.getOutputStream();
                }
                req += (char)x;
            }
            return req;
        }

        public String getRequest(int i) {
            return requests.get(i);
        }

        public void run() {
            try {
                int index=0;
                s = ss.accept();
                in = s.getInputStream();
                out = s.getOutputStream();
                while (true) {
                    requests.add(readRequest());
                    out.write(responses[index++].getBytes(US_ASCII));
                }
            } catch (Exception e) {
                System.err.println("Delete this: " + e);
                //e.printStackTrace();
            }
        }
    }

    static class ProxySel extends ProxySelector {
        final int port;

        ProxySel(int port) {
            this.port = port;
        }
        @Override
        public List<Proxy> select(URI uri) {
          return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("localhost", port)));
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {}

    }

    static class ProxyAuth extends Authenticator {
        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            if (getRequestorType() != RequestorType.PROXY) {
                // We only want to handle proxy authentication here
                return null;
            }
            return new PasswordAuthentication("proxyUser", "proxyPwd".toCharArray());
        }
    }

    static class ServerAuth extends Authenticator {
        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            if (getRequestorType() != RequestorType.SERVER) {
                // We only want to handle proxy authentication here
                return null;
            }
            return new PasswordAuthentication("serverUser", "serverPwd".toCharArray());
        }
    }

    static void assertEquals(int a, int b) {
        if (a != b) {
            String msg = String.format("Error: expected %d Got %d", a, b);
            throw new RuntimeException(msg);
        }
    }

    static void assertEquals(String s1, String s2) {
        if (!s1.equals(s2)) {
            String msg = String.format("Error: expected %s Got %s", s1, s2);
            throw new RuntimeException(msg);
        }
    }

    static void assertContains(String container, String containee) {
        if (!container.contains(containee)) {
            String msg = String.format("Error: expected %s Got %s", container, containee);
            throw new RuntimeException(msg);
        }
    }

    static void assertPattern(String pattern, String candidate) {
        Pattern pat = Pattern.compile(pattern, Pattern.DOTALL | Pattern.MULTILINE);
        Matcher matcher = pat.matcher(candidate);
        if (!matcher.matches()) {
            String msg = String.format("Error: expected %s Got %s", pattern, candidate);
            throw new RuntimeException(msg);
        }
    }
}
