package com.collabcase.codegen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Component;

@Component
public class ZipService {
    public byte[] zip(Map<String,byte[]> files){try{ByteArrayOutputStream output=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(output)){for(var entry:files.entrySet()){String path=safe(entry.getKey());zip.putNextEntry(new ZipEntry(path));zip.write(entry.getValue());zip.closeEntry();}}return output.toByteArray();}catch(Exception e){throw new IllegalStateException("No se pudo crear el ZIP",e);}}
    /** Archivo que se copia al ZIP por streaming (el APK pesa más de 100 MB). */
    public record StreamedEntry(String path, Supplier<InputStream> content) {}
    public void write(Map<String,byte[]> files,List<StreamedEntry> streamed,OutputStream output) throws IOException {
        ZipOutputStream zip=new ZipOutputStream(output);
        for(var entry:files.entrySet()){zip.putNextEntry(new ZipEntry(safe(entry.getKey())));zip.write(entry.getValue());zip.closeEntry();}
        // un APK ya viene comprimido: guardarlo sin volver a comprimir ahorra CPU en el servidor
        zip.setLevel(Deflater.NO_COMPRESSION);
        for(var entry:streamed){try(InputStream in=entry.content().get()){if(in==null)continue;zip.putNextEntry(new ZipEntry(safe(entry.path())));in.transferTo(zip);zip.closeEntry();}}
        zip.finish();
    }
    private String safe(String path){String normalized=path.replace('\\','/');if(normalized.startsWith("/")||normalized.contains("../")||normalized.contains(":")||normalized.isBlank())throw new IllegalArgumentException("Ruta ZIP insegura");return normalized;}
}
