package com.collabcase.runtime.sync;

import jakarta.persistence.*;
import lombok.Getter;

@Entity
@Table(name="runtime_sync_sequence_values")
@SequenceGenerator(name="runtime_sync_version",sequenceName="runtime_sync_version_seq",allocationSize=1)
@Getter
public class RuntimeSyncSequence {
    @Id @GeneratedValue(strategy=GenerationType.SEQUENCE,generator="runtime_sync_version") private Long value;
}
