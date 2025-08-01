package com.hibegin.common.dao.gson;

import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.hibegin.common.dao.ResultValueConvertUtils;

public class FlexibleBooleanAdapter extends TypeAdapter<Boolean> {
    @Override
    public void write(JsonWriter out, Boolean value) throws java.io.IOException {
        out.value(value);
    }

    @Override
    public Boolean read(JsonReader in) throws java.io.IOException {
        switch (in.peek()) {
            case BOOLEAN:
                return in.nextBoolean();
            case NUMBER:
                return in.nextDouble() != 0;
            case STRING:
                String str = in.nextString();
                return ResultValueConvertUtils.toBoolean(str);
            case NULL:
                in.nextNull();
                return false;
            default:
                throw new JsonParseException("Cannot parse to boolean");
        }
    }
}