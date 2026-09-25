package com.binhlaig.pos.admin;

import com.binhlaig.pos.admin.dto.AdminShopRegisterResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminShopControllerTest {

    private AdminShopService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(AdminShopService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminShopController(service)).build();
    }

    @Test
    void bindsFrontendMultipartFieldNamesIncludingEmailPhoneAndImage() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "image", "owner.png", "image/png", new byte[]{1, 2, 3});
        when(service.registerShop(
                "owner", "owner@example.com", "+959123456789", "StrongPassword123!",
                "ADMIN", 9112L, "SHP-ABC", "Binhlaig Mart", "Yangon",
                "SUPERMARKET", "ACTIVE", "PRO", 30, image))
                .thenReturn(new AdminShopRegisterResponse(
                        "Shop and owner account created successfully",
                        "owner", "owner@example.com", "+959123456789", "ADMIN",
                        9112L, "SHP-ABC", "Binhlaig Mart", "Yangon"));

        mockMvc.perform(multipart("/api/admin/shops/register")
                        .file(image)
                        .param("username", "owner")
                        .param("email", "owner@example.com")
                        .param("phone", "+959123456789")
                        .param("password", "StrongPassword123!")
                        .param("role", "ADMIN")
                        .param("shopId", "9112")
                        .param("shopCode", "SHP-ABC")
                        .param("shopName", "Binhlaig Mart")
                        .param("address", "Yangon")
                        .param("businessType", "SUPERMARKET")
                        .param("status", "ACTIVE")
                        .param("subscriptionPlan", "PRO")
                        .param("subscriptionDays", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("owner@example.com"))
                .andExpect(jsonPath("$.phone").value("+959123456789"));

        verify(service).registerShop(
                "owner", "owner@example.com", "+959123456789", "StrongPassword123!",
                "ADMIN", 9112L, "SHP-ABC", "Binhlaig Mart", "Yangon",
                "SUPERMARKET", "ACTIVE", "PRO", 30, image);
    }
}
