package com.binhlaig.pos.owner;

import com.binhlaig.pos.admin.Shop;
import com.binhlaig.pos.admin.ShopRepository;
import com.binhlaig.pos.auth.Role;
import com.binhlaig.pos.owner.dto.OwnerProfileResponse;
import com.binhlaig.pos.owner.dto.ShopCodeUpdateRequest;
import com.binhlaig.pos.owner.dto.ShopCodeUpdateResponse;
import com.binhlaig.pos.owner.dto.UpdateOwnerProfileRequest;
import com.binhlaig.pos.user.User;
import com.binhlaig.pos.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OwnerServiceTest {

    @Mock UserRepository userRepository;
    @Mock ShopRepository shopRepository;
    @Mock JdbcTemplate jdbcTemplate;

    private OwnerService service;
    private User owner;
    private Shop shop;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        service = new OwnerService(userRepository, shopRepository, jdbcTemplate);
        owner = User.builder()
                .id(1L).username("owner").email("old@example.com").phone("0900")
                .role(Role.ADMIN).shopId(9112L).shopCode("SHP-OLD")
                .shopName("Old Mart").address("Old Address").build();
        shop = Shop.builder()
                .id(9112L).shopCode("SHP-OLD").shopName("Old Mart")
                .address("Old Address").businessType("SUPERMARKET").build();
        authentication = new UsernamePasswordAuthenticationToken(
                "owner", null, List.of(() -> "ROLE_ADMIN"));
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(shopRepository.findById(9112L)).thenReturn(Optional.of(shop));
    }

    @Test
    void fetchesCurrentAuthenticatedOwnerProfile() {
        OwnerProfileResponse response = service.getProfile(authentication);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo("old@example.com");
        assertThat(response.shopId()).isEqualTo(9112L);
        assertThat(response.shopCode()).isEqualTo("SHP-OLD");
    }

    @Test
    void updatesEmailPhoneShopNameAndAddressWithoutChangingShopIdentity() {
        OwnerProfileResponse response = service.updateProfile(authentication,
                new UpdateOwnerProfileRequest(
                        "  NEW-OWNER@Example.COM ", "+959123456789",
                        "Binhlaig Mart", "Yangon"));

        assertThat(response.email()).isEqualTo("new-owner@example.com");
        assertThat(response.phone()).isEqualTo("+959123456789");
        assertThat(response.shopName()).isEqualTo("Binhlaig Mart");
        assertThat(response.address()).isEqualTo("Yangon");
        assertThat(owner.getShopId()).isEqualTo(9112L);
        assertThat(shop.getId()).isEqualTo(9112L);
        verify(userRepository).saveAndFlush(owner);
        verify(shopRepository).save(shop);
    }

    @Test
    void rejectsDuplicateEmailWithConflict() {
        when(userRepository.existsByEmailIgnoreCaseAndIdNot("taken@example.com", 1L))
                .thenReturn(true);

        assertThatThrownBy(() -> service.updateProfile(authentication,
                new UpdateOwnerProfileRequest("taken@example.com", null, null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsInvalidEmail() {
        assertThatThrownBy(() -> service.updateProfile(authentication,
                new UpdateOwnerProfileRequest("not-an-email", null, null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void authenticatedOwnerCannotTargetAnotherShop() {
        service.updateProfile(authentication,
                new UpdateOwnerProfileRequest(null, "  +81 90 1234 5678  ", null, null));

        assertThat(owner.getShopId()).isEqualTo(9112L);
        verify(shopRepository).findById(9112L);
        verify(shopRepository, never()).findById(9999L);
    }

    @Test
    void updatesShopCodeAcrossCurrentDataAndRequiresReauthentication() {
        when(shopRepository.existsByShopCodeIgnoreCase("SHP-BINHLAIG")).thenReturn(false);
        when(userRepository.findAllByShopId(9112L)).thenReturn(List.of(owner));

        ShopCodeUpdateResponse response = service.updateShopCode(
                authentication, new ShopCodeUpdateRequest(" shp-binhlaig "));

        assertThat(response.reauthenticationRequired()).isTrue();
        assertThat(response.message()).contains("sign in again");
        assertThat(shop.getShopCode()).isEqualTo("SHP-BINHLAIG");
        assertThat(owner.getShopCode()).isEqualTo("SHP-BINHLAIG");
        assertThat(owner.getShopId()).isEqualTo(9112L);
        verify(jdbcTemplate, times(7)).update(anyString(), eq("SHP-BINHLAIG"), eq(9112L));
    }

    @Test
    void rejectsDuplicateShopCodeWithoutChangingShopId() {
        when(shopRepository.existsByShopCodeIgnoreCase("SHP-TAKEN")).thenReturn(true);

        assertThatThrownBy(() -> service.updateShopCode(
                authentication, new ShopCodeUpdateRequest("SHP-TAKEN")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(owner.getShopId()).isEqualTo(9112L);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void rejectsInvalidNormalizedShopCode() {
        assertThatThrownBy(() -> service.updateShopCode(
                authentication, new ShopCodeUpdateRequest("BAD CODE")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
