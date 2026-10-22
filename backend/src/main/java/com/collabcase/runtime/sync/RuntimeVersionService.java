package com.collabcase.runtime.sync;
import org.springframework.stereotype.Service;
@Service public class RuntimeVersionService{private final RuntimeSyncSequenceRepository repository;public RuntimeVersionService(RuntimeSyncSequenceRepository repository){this.repository=repository;}public long next(){return repository.saveAndFlush(new RuntimeSyncSequence()).getValue();}}
