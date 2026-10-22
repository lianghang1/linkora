package com.linkora.serialize.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * SDK 唯一的 JSON 共享工具：全局单例 {@link ObjectMapper}，SDK 内部
 * （{@link MessageJsonCodec}、{@link JacksonPayloadSerializer}、上行发布等）与
 * 宿主业务代码统一使用同一套 JSON 语义，各处不再自行 new/configure ObjectMapper。
 *
 * <p>统一配置（容错优先，适配报文字段演进）：</p>
 * <ul>
 *   <li>时区 Asia/Shanghai；java.util.Date 与 java.time（jsr310）统一按
 *       {@code yyyy-MM-dd HH:mm:ss}（日期 {@code yyyy-MM-dd}、时间 {@code HH:mm:ss}）读写；</li>
 *   <li>反序列化容忍：未知字段忽略、未知枚举为 null、空串转 null、
 *       null 不再导致基本类型/构造器失败；</li>
 *   <li>序列化：BigDecimal 按原样输出、浮点统一 BigDecimal、空 bean 不抛错、
 *       枚举按 {@code toString()} 输出。</li>
 * </ul>
 *
 * <p>失败统一抛 {@link LinkoraException}（错误码 SERIALIZATION_ERROR）；
 * 单例在静态初始化时完成配置、此后只读，所有方法线程安全。</p>
 *
 * @author lianghang
 */
public final class LinkoraJsonUtil {

    private static final Logger log = LoggerFactory.getLogger(LinkoraJsonUtil.class);

    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    private static final String DATE_PATTERN = "yyyy-MM-dd";
    private static final String TIME_PATTERN = "HH:mm:ss";

    private static final ObjectMapper MAPPER = configure(new ObjectMapper());

    private LinkoraJsonUtil() {
    }

    /** 统一配置共享单例：时区/日期格式 + JavaTimeModule + 容错特性，仅静态初始化调用一次。 */
    private static ObjectMapper configure(ObjectMapper mapper) {
        mapper.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        mapper.setDateFormat(new SimpleDateFormat(DATE_TIME_PATTERN));
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern(DATE_PATTERN);
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern(TIME_PATTERN);
        JavaTimeModule javaTimeModule = new JavaTimeModule();
        javaTimeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(dateTimeFormatter));
        javaTimeModule.addSerializer(LocalDate.class, new LocalDateSerializer(dateFormatter));
        javaTimeModule.addSerializer(LocalTime.class, new LocalTimeSerializer(timeFormatter));
        javaTimeModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(dateTimeFormatter));
        javaTimeModule.addDeserializer(LocalDate.class, new LocalDateDeserializer(dateFormatter));
        javaTimeModule.addDeserializer(LocalTime.class, new LocalTimeDeserializer(timeFormatter));
        mapper.registerModule(javaTimeModule);

        mapper.enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.configure(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES, false);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false);
        mapper.configure(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES, false);
        mapper.configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true);
        mapper.configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        mapper.configure(SerializationFeature.WRITE_ENUMS_USING_TO_STRING, true);
        return mapper;
    }

    /** 获取共享的单例 {@link ObjectMapper}（已按统一语义配置，只读使用，勿改动其配置）。 */
    public static ObjectMapper getObjectMapper() {
        return MAPPER;
    }

    /** 对象转 JSON 字符串；null 返回 null。 */
    public static String toJson(Object object) {
        if (object == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(object);
        } catch (IOException e) {
            log.error("对象转 JSON 失败", e);
            throw new LinkoraException(LinkoraErrorCode.SERIALIZATION_ERROR,
                    "对象转 JSON 失败: " + object.getClass().getName(), e);
        }
    }

    /** 对象转 JSON 字节（UTF-8）；null 返回 null。 */
    public static byte[] toJsonBytes(Object object) {
        if (object == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsBytes(object);
        } catch (IOException e) {
            log.error("对象转 JSON 字节失败", e);
            throw new LinkoraException(LinkoraErrorCode.SERIALIZATION_ERROR,
                    "对象转 JSON 字节失败: " + object.getClass().getName(), e);
        }
    }

    /** JSON 字符串转对象；空白输入返回 null。 */
    public static <T> T fromJson(String json, Class<T> type) {
        if (isBlank(json)) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            log.error("JSON 转对象失败: {}", json, e);
            throw new LinkoraException(LinkoraErrorCode.SERIALIZATION_ERROR,
                    "JSON 转对象失败: " + type.getName(), e);
        }
    }

    /** JSON 字符串转复杂泛型对象（List/Map 等）；空白输入返回 null。 */
    public static <T> T fromJson(String json, TypeReference<T> typeReference) {
        if (isBlank(json)) {
            return null;
        }
        try {
            return MAPPER.readValue(json, typeReference);
        } catch (IOException e) {
            log.error("JSON 转复杂对象失败: {}", json, e);
            throw new LinkoraException(LinkoraErrorCode.SERIALIZATION_ERROR,
                    "JSON 转复杂对象失败", e);
        }
    }

    /** JSON 字符串解析为 {@link JsonNode} 树；空白输入返回 null。 */
    public static JsonNode readTree(String json) {
        if (isBlank(json)) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new LinkoraException(LinkoraErrorCode.SERIALIZATION_ERROR,
                    "payload 不是合法 JSON: " + e.getMessage(), e);
        }
    }

    /** 字节数组解析为 {@link JsonNode} 树；空输入返回 null。 */
    public static JsonNode readTree(byte[] json) {
        if (json == null || json.length == 0) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new LinkoraException(LinkoraErrorCode.SERIALIZATION_ERROR,
                    "payload 不是合法 JSON: " + e.getMessage(), e);
        }
    }

    /** JSON 字符串转 Map；空白输入返回空 Map。 */
    public static Map<String, Object> toMap(String json) {
        if (isBlank(json)) {
            return new HashMap<String, Object>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (IOException e) {
            log.error("JSON 转 Map 失败: {}", json, e);
            throw new LinkoraException(LinkoraErrorCode.SERIALIZATION_ERROR, "JSON 转 Map 失败", e);
        }
    }

    /** 对象转 Map（基于 Jackson 属性语义）；null 返回空 Map。 */
    public static Map<String, Object> toMap(Object object) {
        if (object == null) {
            return new HashMap<String, Object>();
        }
        return MAPPER.convertValue(object, new TypeReference<Map<String, Object>>() { });
    }

    /** 对象字段转换为目标类型；null 返回 null。 */
    public static <T> T convertValue(Object fromValue, Class<T> toType) {
        if (fromValue == null) {
            return null;
        }
        return MAPPER.convertValue(fromValue, toType);
    }

    /** 判断字符串是否为合法 JSON。 */
    public static boolean isValidJson(String json) {
        if (isBlank(json)) {
            return false;
        }
        try {
            MAPPER.readTree(json);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        if (value == null) {
            return true;
        }
        int length = value.length();
        for (int i = 0; i < length; i++) {
            if (!Character.isWhitespace(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
