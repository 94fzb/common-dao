package com.hibegin.common.dao;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.HashMap;
import java.util.Map;

public class ResultBeanUtils {

    private static final Gson gson = new GsonBuilder().registerTypeAdapter(Boolean.class, new FlexibleBooleanAdapter())
            .registerTypeAdapter(boolean.class, new FlexibleBooleanAdapter()).create();

    public static <T> T convert(Object obj, Class<T> tClass) {
        String jsonStr = gson.toJson(obj);
        return gson.fromJson(jsonStr, tClass);
    }

    public static void main(String[] args) {
        Map<String, Object> map = new HashMap<>();
        map.put("testBoolean", "1.0");
        map.put("testBoolean2", "1.0");
        map.put("testBoolean3", null);
        map.put("testLong", "");
        TestBean convert = convert(map, TestBean.class);
        System.out.println("convert = " + gson.toJson(convert));
    }

    public static class TestBean {
        private Boolean testBoolean;
        private boolean testBoolean2;
        private Boolean testBoolean3;
        private Long testLong;

        public Boolean getTestBoolean() {
            return testBoolean;
        }

        public void setTestBoolean(Boolean testBoolean) {
            this.testBoolean = testBoolean;
        }

        public boolean isTestBoolean2() {
            return testBoolean2;
        }

        public void setTestBoolean2(boolean testBoolean2) {
            this.testBoolean2 = testBoolean2;
        }

        public Boolean getTestBoolean3() {
            return testBoolean3;
        }

        public void setTestBoolean3(Boolean testBoolean3) {
            this.testBoolean3 = testBoolean3;
        }

        public Long getTestLong() {
            return testLong;
        }

        public void setTestLong(Long testLong) {
            this.testLong = testLong;
        }
    }

}
