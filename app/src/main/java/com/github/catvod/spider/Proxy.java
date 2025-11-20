package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.ProxyVideo;
import com.github.catvod.utils.Util;
import com.google.gson.Gson;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Proxy extends Spider {

    private static int port = -1;

    public static Object[] proxy(Map<String, String> params) throws Exception {
        switch (params.get("do")) {
            case "ck":
                return new Object[]{200, "text/plain; charset=utf-8", new ByteArrayInputStream("ok".getBytes("UTF-8"))};
            case "ali":
                return Ali.proxy(params);
            case "quark":
                return Quark.proxy(params);
            case "uc":
                return UC.proxy(params);
            case "bili":
                return Bili.proxy(params);
            case "webdav":
                return WebDAV.vod(params);
            case "local":
                return Local.proxy(params);
            case "proxy":
                return commonProxy(params);
            case "emby_multithread":
                return embyMultiThreadProxy(params);
            default:
                return null;
        }
    }

    private static final List<String> keys = Arrays.asList("url", "header", "do", "Content-Type", "User-Agent", "Host");

    private static Object[] commonProxy(Map<String, String> params) throws Exception {
        String url = Util.base64Decode(params.get("url"));
        Map<String, String> header = new Gson().fromJson(Util.base64Decode(params.get("header")), Map.class);
        if (header == null) header = new HashMap<>();
        List<String> keys = Arrays.asList("range", "connection", "accept-encoding");
        for (String key : params.keySet()) {
            if (keys.contains(key.toLowerCase())) {
                header.put(key, params.get(key));
            }
        }
        /*for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!keys.contains(entry.getKey())) header.put(entry.getKey(), entry.getValue());
        }*/
        return ProxyVideo.proxyMultiThread(url, header);
    }

    private static Object[] embyMultiThreadProxy(Map<String, String> params) throws Exception {
        try {
            String url = Util.base64Decode(params.get("url"));
            Map<String, String> header = new Gson().fromJson(Util.base64Decode(params.get("header")), Map.class);
            if (header == null) header = new HashMap<>();

            // 从params中获取播放器传递的所有header（播放器会传递Range、Cookie、Referer等）
            // 这些header对于视频播放和断点续传非常重要
            // 注意：不包含Host，因为Host会由OkHttp根据目标URL自动设置
            List<String> headerKeys = Arrays.asList(
                "range", "Range",
                "connection", "Connection",
                "accept-encoding", "Accept-Encoding",
                "accept", "Accept",
                "accept-language", "Accept-Language",
                "cookie", "Cookie",
                "referer", "Referer",
                "origin", "Origin",
                "user-agent", "User-Agent"
            );

            for (String key : params.keySet()) {
                // 使用不区分大小写的匹配
                for (String headerKey : headerKeys) {
                    if (key.equalsIgnoreCase(headerKey)) {
                        header.put(key, params.get(key));
                        break;
                    }
                }
            }

            // 确保移除可能存在的Host头（播放器的Host是127.0.0.1:9978，不应该转发）
            header.remove("host");
            header.remove("Host");


            // 获取线程数参数，默认使用16线程
            int threadNum = 16;
            if (params.containsKey("thread")) {
                try {
                    threadNum = Integer.parseInt(params.get("thread"));
                    // 限制线程数范围：1-32
                    if (threadNum < 1) threadNum = 1;
                    if (threadNum > 32) threadNum = 32;
                } catch (NumberFormatException e) {
                    threadNum = 16;
                }
            }

            SpiderDebug.log("embyMultiThreadProxy: url=" + url + ", thread=" + threadNum);
            SpiderDebug.log("embyMultiThreadProxy: headers=" + header.keySet());
            SpiderDebug.log("embyMultiThreadProxy: User-Agent=" + header.get("User-Agent"));

            // 使用多线程代理下载，传递线程数参数
            Object[] result = ProxyVideo.proxyMultiThread(url, header, threadNum);

            // 如果多线程下载失败（返回null），降级到单线程代理
            if (result == null) {
                SpiderDebug.log("embyMultiThreadProxy failed, fallback to single thread proxy");
                result = ProxyVideo.proxy(url, header);
            }

            return result;
        } catch (Exception e) {
            SpiderDebug.log("embyMultiThreadProxy error: " + e.getMessage());
            e.printStackTrace();

            // 发生异常时，尝试降级到单线程代理
            try {
                String url = Util.base64Decode(params.get("url"));
                Map<String, String> header = new Gson().fromJson(Util.base64Decode(params.get("header")), Map.class);
                if (header == null) header = new HashMap<>();

                SpiderDebug.log("embyMultiThreadProxy fallback to single thread proxy due to exception");
                return ProxyVideo.proxy(url, header);
            } catch (Exception fallbackException) {
                SpiderDebug.log("embyMultiThreadProxy fallback also failed: " + fallbackException.getMessage());
                fallbackException.printStackTrace();
                throw fallbackException;
            }
        }
    }


    static void adjustPort() {
        if (Proxy.port > 0) return;
        int port = 9978;
        while (port < 10000) {
            String resp = OkHttp.string("http://127.0.0.1:" + port + "/proxy?do=ck", null);
            if (resp.equals("ok")) {
                SpiderDebug.log("Found local server port " + port);
                Proxy.port = port;
                break;
            }
            port++;
        }
    }

    public static int getPort() {
        adjustPort();
        return port;
    }

    public static String getUrl() {
        adjustPort();
        return "http://127.0.0.1:" + port + "/proxy";
    }
}
