package com.memecreator.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoginIdentityRepository extends JpaRepository<LoginIdentity, Long> {

    Optional<LoginIdentity> findByProviderAndSubject(String provider, String subject);
}
