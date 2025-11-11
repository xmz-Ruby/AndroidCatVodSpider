package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Util;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.*;

public class TgSearchQuark extends Cloud {
    private static final String KEY_API_URLS = "api_urls";
    private static final String KEY_DOMAIN_MAP = "siteurl";
    private static final String KEY_SOURCES = "sources";
    private static final String KEY_CHANNELS = "channels";
    private static final String KEY_CONC = "conc";
    private static final String KEY_REFRESH = "refresh";
    private static final String KEY_RES = "res";
    private static final String KEY_SRC = "src";
    private static final String KEY_PLUGINS = "plugins";
    private static final String KEY_CLOUD_TYPES = "cloud_types";
    private static final String KEY_EXT = "ext";
    private static final int DEFAULT_PAGE_SIZE = 10;

    private List<String> apiUrls = new ArrayList<>();
    private Map<String, String> domainMap = new HashMap<String, String>() {{
        put("alipan", "阿里");
        put("aliyundrive", "阿里");
        put("quark", "夸克");
        put("115cdn", "115");
        put("baidu.com", "百度");
        put("uc", "UC");
        put("189.cn", "天翼");
        put("139.com", "移动");
        put("123", "123盘");
    }};
    private Set<String> sources = new HashSet<>();
    private String[] extInfos = null;

    // 新增配置参数
    private String channels = null;
    private Integer conc = null;
    private Boolean refresh = null;
    private String res = null;
    private String src = null;
    private String plugins = null;
    private String cloudTypes = null;
    private String ext = null;

    @Override
    public synchronized void init(Context context, String extend) throws Exception {
        super.init(context, extend);

        this.apiUrls.clear();

        if (!TextUtils.isEmpty(extend)) {
            try {
                if (extend.contains("###")) {
                    String[] infos = extend.split("###");
                    this.extInfos = infos;

                    if (infos.length > 0 && !TextUtils.isEmpty(infos[0])) {
                        processExtendConfig(infos[0]);
                    }
                } else {
                    this.extInfos = new String[]{extend};
                    processExtendConfig(extend);
                }
            } catch (Exception e) {
            }
        }
        this.extInfos = null; // 重置，避免内存泄漏
    }

    private void processExtendConfig(String config) {
        try {
            // 检查是否为HTTP URL
            if (isValidUrl(config)) {
                if (!this.apiUrls.contains(config)) {
                    this.apiUrls.add(config);
                }
                return;
            }

            // 尝试JSON格式解析
            JsonObject ext = Json.safeObject(config);

            // 处理API URLs配置
            processApiUrls(ext);

            // 处理域名映射配置
            processDomainMap(ext);

            // 处理来源过滤配置
            processSources(ext, true);

            // 处理新增的GET请求参数配置
            processSearchParams(ext);
        } catch (Exception e) {
            // 可以添加日志记录
        }
    }

    private void processApiUrls(JsonObject config) {
        if (config.has(KEY_API_URLS) && config.get(KEY_API_URLS).isJsonArray()) {
            JsonArray urlsArray = config.getAsJsonArray(KEY_API_URLS);
            for (JsonElement element : urlsArray) {
                if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                    String url = element.getAsString();
                    if (isValidUrl(url) && !this.apiUrls.contains(url)) {
                        this.apiUrls.add(url);
                    }
                }
            }
        }
    }

    private void processDomainMap(JsonObject config) {
        if (config.has(KEY_DOMAIN_MAP) && config.get(KEY_DOMAIN_MAP).isJsonObject()) {
            JsonObject customDomains = config.getAsJsonObject(KEY_DOMAIN_MAP);
            for (Map.Entry<String, JsonElement> entry : customDomains.entrySet()) {
                if (entry == null || entry.getValue() == null) continue;

                String domain = entry.getKey();
                String sourceName = "";
                try {
                    sourceName = entry.getValue().getAsString();
                } catch (Exception e) {
                    continue;
                }

                if (!TextUtils.isEmpty(domain) && !TextUtils.isEmpty(sourceName) && !domainMap.containsKey(domain)) {
                    domainMap.put(domain, sourceName);
                }
            }
        }
    }

    private void processSources(JsonObject config, boolean clearExisting) {
        if (config.has(KEY_SOURCES) && config.get(KEY_SOURCES).isJsonArray()) {
            if (clearExisting) {
                this.sources.clear();
            }

            JsonArray sourcesArray = config.getAsJsonArray(KEY_SOURCES);
            for (JsonElement element : sourcesArray) {
                if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                    String source = element.getAsString();
                    if (!TextUtils.isEmpty(source) && !sources.contains(source)) {
                        sources.add(source);
                    }
                }
            }
        }
    }

    private void processSearchParams(JsonObject config) {
        // 处理 channels 参数
        if (config.has(KEY_CHANNELS)) {
            JsonElement channelsElement = config.get(KEY_CHANNELS);
            if (channelsElement.isJsonPrimitive() && channelsElement.getAsJsonPrimitive().isString()) {
                this.channels = channelsElement.getAsString();
            } else if (channelsElement.isJsonArray()) {
                // 如果是数组，转换为逗号分隔的字符串
                JsonArray channelsArray = channelsElement.getAsJsonArray();
                List<String> channelsList = new ArrayList<>();
                for (JsonElement element : channelsArray) {
                    if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                        channelsList.add(element.getAsString());
                    }
                }
                if (!channelsList.isEmpty()) {
                    this.channels = TextUtils.join(",", channelsList);
                }
            }
        }

        // 处理 conc 参数
        if (config.has(KEY_CONC) && !config.get(KEY_CONC).isJsonNull()) {
            try {
                this.conc = config.get(KEY_CONC).getAsInt();
            } catch (Exception e) {
                // 忽略无效值
            }
        }

        // 处理 refresh 参数
        if (config.has(KEY_REFRESH) && !config.get(KEY_REFRESH).isJsonNull()) {
            try {
                this.refresh = config.get(KEY_REFRESH).getAsBoolean();
            } catch (Exception e) {
                // 忽略无效值
            }
        }

        // 处理 res 参数
        if (config.has(KEY_RES) && !config.get(KEY_RES).isJsonNull()) {
            try {
                String resValue = config.get(KEY_RES).getAsString();
                if ("all".equals(resValue) || "results".equals(resValue) || "merge".equals(resValue)) {
                    this.res = resValue;
                }
            } catch (Exception e) {
                // 忽略无效值
            }
        }

        // 处理 src 参数
        if (config.has(KEY_SRC) && !config.get(KEY_SRC).isJsonNull()) {
            try {
                String srcValue = config.get(KEY_SRC).getAsString();
                if ("all".equals(srcValue) || "tg".equals(srcValue) || "plugin".equals(srcValue)) {
                    this.src = srcValue;
                }
            } catch (Exception e) {
                // 忽略无效值
            }
        }

        // 处理 plugins 参数
        if (config.has(KEY_PLUGINS)) {
            JsonElement pluginsElement = config.get(KEY_PLUGINS);
            if (pluginsElement.isJsonPrimitive() && pluginsElement.getAsJsonPrimitive().isString()) {
                this.plugins = pluginsElement.getAsString();
            } else if (pluginsElement.isJsonArray()) {
                // 如果是数组，转换为逗号分隔的字符串
                JsonArray pluginsArray = pluginsElement.getAsJsonArray();
                List<String> pluginsList = new ArrayList<>();
                for (JsonElement element : pluginsArray) {
                    if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                        pluginsList.add(element.getAsString());
                    }
                }
                if (!pluginsList.isEmpty()) {
                    this.plugins = TextUtils.join(",", pluginsList);
                }
            }
        }

        // 处理 cloud_types 参数
        if (config.has(KEY_CLOUD_TYPES)) {
            JsonElement cloudTypesElement = config.get(KEY_CLOUD_TYPES);
            if (cloudTypesElement.isJsonPrimitive() && cloudTypesElement.getAsJsonPrimitive().isString()) {
                this.cloudTypes = cloudTypesElement.getAsString();
            } else if (cloudTypesElement.isJsonArray()) {
                // 如果是数组，转换为逗号分隔的字符串
                JsonArray cloudTypesArray = cloudTypesElement.getAsJsonArray();
                List<String> cloudTypesList = new ArrayList<>();
                for (JsonElement element : cloudTypesArray) {
                    if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                        cloudTypesList.add(element.getAsString());
                    }
                }
                if (!cloudTypesList.isEmpty()) {
                    this.cloudTypes = TextUtils.join(",", cloudTypesList);
                }
            }
        }

        // 处理 ext 参数（JSON格式的扩展参数）
        if (config.has(KEY_EXT)) {
            JsonElement extElement = config.get(KEY_EXT);
            if (extElement.isJsonObject()) {
                this.ext = extElement.toString();
            } else if (extElement.isJsonPrimitive() && extElement.getAsJsonPrimitive().isString()) {
                this.ext = extElement.getAsString();
            }
        }
    }

    private boolean isValidUrl(String url) {
        return !TextUtils.isEmpty(url) && (url.startsWith("http://") || url.startsWith("https://"));
    }


    private Map<String, String> getHeader() {
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", Util.CHROME);
        return header;
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        if (key == null || key.trim().isEmpty()) {
            return Result.error("关键词不能为空");
        }

        return performSearch(key, 1, DEFAULT_PAGE_SIZE);
    }

    private String performSearch(String key, int page, int pageSize) throws Exception {
        List<Vod> list = new ArrayList<>();
        int total = 0;

        Map<String, String> params = new HashMap<>();
        params.put("kw", key);
        params.put("page", String.valueOf(page));
        params.put("size", String.valueOf(pageSize));

        // 添加配置的可选参数
        if (!TextUtils.isEmpty(this.channels)) {
            params.put("channels", this.channels);
        }
        if (this.conc != null) {
            params.put("conc", String.valueOf(this.conc));
        }
        if (this.refresh != null) {
            params.put("refresh", String.valueOf(this.refresh));
        }
        if (!TextUtils.isEmpty(this.res)) {
            params.put("res", this.res);
        }
        if (!TextUtils.isEmpty(this.src)) {
            params.put("src", this.src);
        }
        if (!TextUtils.isEmpty(this.plugins)) {
            params.put("plugins", this.plugins);
        }
        if (!TextUtils.isEmpty(this.cloudTypes)) {
            params.put("cloud_types", this.cloudTypes);
        }
        if (!TextUtils.isEmpty(this.ext)) {
            params.put("ext", this.ext);
        }

        for (String apiUrl : apiUrls) {
            if (!isValidUrl(apiUrl)) continue;

            try {
                OkResult result = OkHttp.get(apiUrl, params, getHeader());
                if (result.getCode() == 500 || TextUtils.isEmpty(result.getBody())) continue;

                JsonObject jsonObject = Json.safeObject(result.getBody());
                if (!jsonObject.has("code") || jsonObject.get("code").getAsInt() != 0 || !jsonObject.has("data"))
                    continue;

                JsonObject data = jsonObject.getAsJsonObject("data");

                total = data.has("total") && !data.get("total").isJsonNull() ? data.get("total").getAsInt() : 0;

                // 直接检查merged_by_type字段，无需三重判断
                if (!data.has("merged_by_type") || !data.get("merged_by_type").isJsonObject()) continue;

                JsonObject mergedByType = data.getAsJsonObject("merged_by_type");

                for (Map.Entry<String, JsonElement> categoryEntry : mergedByType.entrySet()) {
                    if (!categoryEntry.getValue().isJsonArray()) continue;

                    JsonArray items = categoryEntry.getValue().getAsJsonArray();

                    for (JsonElement item : items) {
                        if (!item.isJsonObject()) continue;

                        JsonObject entry = item.getAsJsonObject();

                        String vodUrl = entry.has("url") && !entry.get("url").isJsonNull() ? entry.get("url").getAsString() : "";
                        if (TextUtils.isEmpty(vodUrl)) continue;

                        // 获取来源信息并映射
                        String originalSource = entry.has("source") && !entry.get("source").isJsonNull() ? entry.get("source").getAsString() : "未知来源";
                        String sourceName = mapSource(vodUrl, originalSource);

                        // 获取标题
                        String title = entry.has("note") && !entry.get("note").isJsonNull() ? entry.get("note").getAsString() : "未命名资源";

                        // 获取图片
                        String pic = getFirstImage(entry);

                        // 简化VodPlayBuilder的使用
                        Vod vod = new Vod(vodUrl, title, pic, sourceName + " (" + originalSource + ")");
                        // 由于只有一个播放源，直接设置播放信息
                        vod.setVodPlayFrom(sourceName);
                        vod.setVodPlayUrl("播放源$" + vodUrl);

                        // 来源过滤
                        if (sources.isEmpty() || sources.contains(sourceName)) {
                            list.add(vod);
                        }
                    }
                }

                int pageCount = total > 0 && pageSize > 0 ? (total + pageSize - 1) / pageSize : 1;
                return Result.string(page, pageCount, pageSize, total, list);
            } catch (Exception e) {
                // 出错时继续尝试下一个API
                continue;
            }
        }

        return Result.error("无法连接到任何搜索API");
    }

    // 提取来源映射逻辑为单独方法
    private String mapSource(String vodUrl, String originalSource) {
        for (Map.Entry<String, String> domainEntry : domainMap.entrySet()) {
            if (vodUrl.contains(domainEntry.getKey())) {
                return domainEntry.getValue();
            }
        }
        return originalSource;
    }

    // 提取图片获取逻辑为单独方法
    private String getFirstImage(JsonObject entry) {
        if (entry.has("images") && !entry.get("images").isJsonNull() && entry.get("images").isJsonArray()) {
            JsonArray images = entry.getAsJsonArray("images");
            if (images.size() > 0 && !images.get(0).isJsonNull() && images.get(0).isJsonPrimitive()) {
                return images.get(0).getAsString();
            }
        }
        return "";
    }
}