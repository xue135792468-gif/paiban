package com.paiban.support;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds domain fixtures through the public REST API. */
public final class TestData {

    private TestData() {}

    public static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public static long semester(String token) {
        Response response = RestAssuredHolder.given(token)
                .body(Map.of(
                        "name", "测试学期-" + suffix(),
                        "startDate", "2025-09-01",
                        "endDate", "2026-01-20"))
                .when().post("/api/semesters");
        response.then().statusCode(200);
        long id = response.jsonPath().getLong("id");
        setCurrent(token, id);
        return id;
    }

    public static void setCurrent(String token, long semesterId) {
        RestAssuredHolder.given(token)
                .when().post("/api/semesters/" + semesterId + "/set-current")
                .then().statusCode(200);
    }

    public static long teacher(String token, String name) {
        Response response = RestAssuredHolder.given(token)
                .body(Map.of("name", name, "employeeNo", "T" + suffix()))
                .when().post("/api/teachers");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    public static long room(String token, String name) {
        Response response = RestAssuredHolder.given(token)
                .body(Map.of("name", name, "capacity", 50, "type", "NORMAL"))
                .when().post("/api/rooms");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    public static long course(String token, String name) {
        Response response = RestAssuredHolder.given(token)
                .body(Map.of("name", name, "subject", name, "allowConsecutive", false))
                .when().post("/api/courses");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    public static long grade(String token, long semesterId, String name) {
        Response response = RestAssuredHolder.given(token)
                .body(Map.of("name", name, "sortOrder", 1))
                .queryParam("semesterId", semesterId)
                .when().post("/api/grades");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    public static long adminClass(String token, long semesterId, long gradeId, String name) {
        Response response = RestAssuredHolder.given(token)
                .body(Map.of("gradeId", gradeId, "name", name, "studentCount", 45))
                .queryParam("semesterId", semesterId)
                .when().post("/api/classes");
        response.then().statusCode(200);
        return response.jsonPath().getLong("id");
    }

    /** Minimal holder so helper methods share one RestAssured configuration. */
    static final class RestAssuredHolder {

        private RestAssuredHolder() {}

        static io.restassured.specification.RequestSpecification given(String token) {
            return io.restassured.RestAssured.given()
                    .contentType(ContentType.JSON)
                    .header("Authorization", "Bearer " + token);
        }
    }

    public static List<String> listOf(String... values) {
        return List.of(values);
    }
}
