package com.collabcase.codegen;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Component;

@Component
public class ZipService {
    public byte[] zip(Map<String,byte[]> files){try{ByteArrayOutputStream output=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(output)){for(var entry:files.entrySet()){String path=safe(entry.getKey());zip.putNextEntry(new ZipEntry(path));zip.write(entry.getValue());zip.closeEntry();}}return output.toByteArray();}catch(Exception e){throw new IllegalStateException("No se pudo crear el ZIP",e);}}
    private String safe(String path){String normalized=path.replace('\\','/');if(normalized.startsWith("/")||normalized.contains("../")||normalized.contains(":")||normalized.isBlank())throw new IllegalArgumentException("Ruta ZIP insegura");return normalized;}
}
