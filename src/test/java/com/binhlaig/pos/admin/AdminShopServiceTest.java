package com.binhlaig.pos.admin;

import com.binhlaig.pos.admin.dto.AdminShopRegisterResponse;
import com.binhlaig.pos.auth.Role;
import com.binhlaig.pos.storage.FileStorageService;
import com.binhlaig.pos.user.BusinessType;
import com.binhlaig.pos.user.User;
import com.binhlaig.pos.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminShopServiceTest {

    @Mock ShopRepository shopRepository;
    @Mock UserRepository userRepository;
    @Mock FileStorageService fileStorageService;

    private BCryptPasswordEncoder passwordEncoder;
    private AdminShopService service;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4);
        service = new AdminShopService(
                shopRepository, userRepository, passwordEncoder, fileStorageService);
    }

    @Test
    void superAdminRegistrationSavesNormalizedOwnerContactPasswordAndImage() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "image", "owner.png", "image/png", new byte[]{1, 2, 3});
        when(fileStorageService.saveAvatarImage(image)).thenReturn("/uploads/avatars/owner.png");

        AdminShopRegisterResponse response = service.registerShop(
                " owner ", " Owner@Example.COM ", " +959123456789 ",
                "StrongPassword123!", "ADMIN", 9112L, " shp-abc ",
                " Binhlaig Mart ", " Yangon ", "SUPERMARKET",
                "ACTIVE", "PRO", 30, image);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        User saved = userCaptor.getValue();

        assertThat(saved.getUsername()).isEqualTo("owner");
        assertThat(saved.getEmail()).isEqualTo("owner@example.com");
        assertThat(saved.getPhone()).isEqualTo("+959123456789");
        assertThat(saved.getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getShopId()).isEqualTo(9112L);
        assertThat(saved.getShopCode()).isEqualTo("SHP-ABC");
        assertThat(saved.getBusinessType()).isEqualTo(BusinessType.SUPERMARKET);
        assertThat(saved.getPassword()).isNotEqualTo("StrongPassword123!");
        assertThat(passwordEncoder.matches("StrongPassword123!", saved.getPassword())).isTrue();
        assertThat(saved.getImageUrl()).isEqualTo("/uploads/avatars/owner.png");
        assertThat(response.email()).isEqualTo("owner@example.com");
        assertThat(response.phone()).isEqualTo("+959123456789");
        verify(fileStorageService).saveAvatarImage(image);
        verify(shopRepository).save(any(Shop.class));
    }

    @Test
    void registrationStillWorksWithoutOptionalPhoneOrImage() throws Exception {
        AdminShopRegisterResponse response = service.registerShop(
                "owner", "owner@example.com", null,
                "StrongPassword123!", "ADMIN", 9112L, "SHP-ABC",
                "Binhlaig Mart", "Yangon", "SUPERMARKET",
                "TRIAL", "TRIAL", 14, null);

        assertThat(response.email()).isEqualTo("owner@example.com");
        assertThat(response.phone()).isNull();
        verifyNoInteractions(fileStorageService);
        verify(userRepository).saveAndFlush(any(User.class));
    }

    @Test
    void duplicateEmailIsRejectedWithConflictBeforeDataIsCreated() {
        when(userRepository.existsByEmailIgnoreCase("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.registerShop(
                "owner", " TAKEN@EXAMPLE.COM ", null,
                "StrongPassword123!", "ADMIN", 9112L, "SHP-ABC",
                "Binhlaig Mart", "Yangon", "SUPERMARKET",
                "TRIAL", "TRIAL", 14, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getReason()).isEqualTo("Email already exists.");
                });

        verify(shopRepository, never()).save(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void ownerEmailIsRequired() {
        assertThatThrownBy(() -> service.registerShop(
                "owner", "  ", null,
                "StrongPassword123!", "ADMIN", 9112L, "SHP-ABC",
                "Binhlaig Mart", "Yangon", "SUPERMARKET",
                "TRIAL", "TRIAL", 14, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
