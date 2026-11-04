package com.paiban.imports.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.paiban.common.web.ApiException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

/**
 * 上传失败的提示必须是固定人话：底层 IOException 的文本里带服务器绝对路径，
 * 原样回给用户等于把部署目录结构送出去。
 */
class RecipeSampleStorageTest {

    private final RecipeSampleStorage storage = new RecipeSampleStorage();

    @Test
    void unreadableUploadReturnsFixedTextWithoutServerPaths() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(16L);
        when(file.getOriginalFilename()).thenReturn("sample.csv");
        when(file.getBytes()).thenThrow(
                new IOException("C:\\app\\uploads\\import-recipes\\3\\sample.csv (拒绝访问)"));

        ApiException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                ApiException.class, () -> storage.validate(file));

        assertThat(thrown.getMessage()).isEqualTo("样例文件无法读取，请重新上传");
        assertThat(thrown.getMessage())
                .doesNotContain("C:")
                .doesNotContain("\\")
                .doesNotContain("/")
                .doesNotContain("uploads");
    }
}
