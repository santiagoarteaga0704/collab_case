package com.collabcase.runtime.sync;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RuntimeMutationRepository extends JpaRepository<RuntimeMutation,UUID>{boolean existsByAppIdAndMutationId(UUID appId,UUID mutationId);}
