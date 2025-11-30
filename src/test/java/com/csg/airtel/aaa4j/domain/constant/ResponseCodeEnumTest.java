package com.csg.airtel.aaa4j.domain.constant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResponseCodeEnumTest {

    @Test
    void testEnumValues() {
        ResponseCodeEnum[] values = ResponseCodeEnum.values();
        assertEquals(4, values.length);
    }

    @Test
    void testExceptionControllerLayer() {
        ResponseCodeEnum code = ResponseCodeEnum.EXCEPTION_CONTROLLER_LAYER;
        assertEquals("E1000", code.code());
        assertEquals("Exception Controller Layer Error", code.description());
    }

    @Test
    void testExceptionServiceLayer() {
        ResponseCodeEnum code = ResponseCodeEnum.EXCEPTION_SERVICE_LAYER;
        assertEquals("E1001", code.code());
        assertEquals("Exception Service Layer Error", code.description());
    }

    @Test
    void testExceptionClientLayer() {
        ResponseCodeEnum code = ResponseCodeEnum.EXCEPTION_CLIENT_LAYER;
        assertEquals("E1003", code.code());
        assertEquals("Exception Cache Layer Error", code.description());
    }

    @Test
    void testExceptionDatabaseLayer() {
        ResponseCodeEnum code = ResponseCodeEnum.EXCEPTION_DATABASE_LAYER;
        assertEquals("E1002", code.code());
        assertEquals("Exception in Database Layer Error", code.description());
    }

    @Test
    void testValueOf() {
        assertEquals(ResponseCodeEnum.EXCEPTION_CONTROLLER_LAYER,
                     ResponseCodeEnum.valueOf("EXCEPTION_CONTROLLER_LAYER"));
        assertEquals(ResponseCodeEnum.EXCEPTION_SERVICE_LAYER,
                     ResponseCodeEnum.valueOf("EXCEPTION_SERVICE_LAYER"));
        assertEquals(ResponseCodeEnum.EXCEPTION_CLIENT_LAYER,
                     ResponseCodeEnum.valueOf("EXCEPTION_CLIENT_LAYER"));
        assertEquals(ResponseCodeEnum.EXCEPTION_DATABASE_LAYER,
                     ResponseCodeEnum.valueOf("EXCEPTION_DATABASE_LAYER"));
    }

    @Test
    void testInvalidValueOf() {
        assertThrows(IllegalArgumentException.class, () -> {
            ResponseCodeEnum.valueOf("INVALID_CODE");
        });
    }

    @Test
    void testCodeMethod() {
        assertNotNull(ResponseCodeEnum.EXCEPTION_CONTROLLER_LAYER.code());
        assertNotNull(ResponseCodeEnum.EXCEPTION_SERVICE_LAYER.code());
        assertNotNull(ResponseCodeEnum.EXCEPTION_CLIENT_LAYER.code());
        assertNotNull(ResponseCodeEnum.EXCEPTION_DATABASE_LAYER.code());
    }

    @Test
    void testDescriptionMethod() {
        assertNotNull(ResponseCodeEnum.EXCEPTION_CONTROLLER_LAYER.description());
        assertNotNull(ResponseCodeEnum.EXCEPTION_SERVICE_LAYER.description());
        assertNotNull(ResponseCodeEnum.EXCEPTION_CLIENT_LAYER.description());
        assertNotNull(ResponseCodeEnum.EXCEPTION_DATABASE_LAYER.description());
    }

    @Test
    void testEnumEquality() {
        ResponseCodeEnum code1 = ResponseCodeEnum.EXCEPTION_CONTROLLER_LAYER;
        ResponseCodeEnum code2 = ResponseCodeEnum.EXCEPTION_CONTROLLER_LAYER;
        assertEquals(code1, code2);
        assertSame(code1, code2);
    }

    @Test
    void testAllCodesAreUnique() {
        ResponseCodeEnum[] values = ResponseCodeEnum.values();
        String[] codes = new String[values.length];

        for (int i = 0; i < values.length; i++) {
            codes[i] = values[i].code();
        }

        for (int i = 0; i < codes.length; i++) {
            for (int j = i + 1; j < codes.length; j++) {
                assertNotEquals(codes[i], codes[j],
                        "Response codes should be unique");
            }
        }
    }
}
