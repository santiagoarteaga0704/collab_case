package com.collabcase.runtime.record;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import com.collabcase.runtime.app.RuntimeApp;

@Component
public class RuntimeDefinitionValidator {
    public void validate(RuntimeApp app,String entityType,Map<String,Object> data){Map<String,String> fields=fields(app,entityType);for(var entry:data.entrySet()){String type=fields.get(entry.getKey());if(type==null)bad("Campo no definido para "+entityType+": "+entry.getKey());if(entry.getValue()!=null&&!matches(type,entry.getValue()))bad("Tipo inválido para "+entityType+"."+entry.getKey()+"; se esperaba "+type+".");}}
    @SuppressWarnings("unchecked") private Map<String,String> fields(RuntimeApp app,String entityType){Object raw=app.getDefinition().get("entities");if(!(raw instanceof List<?>))bad("La definición no contiene entidades.");List<?> entities=(List<?>)raw;for(Object item:entities){if(!(item instanceof Map<?,?> entity))continue;String name=String.valueOf(entity.get("name")),javaName=String.valueOf(entity.get("javaName"));if(!entityType.equals(name)&&!entityType.equals(javaName))continue;Map<String,String> result=new LinkedHashMap<>();Object attrs=entity.get("attributes");if(attrs instanceof List<?> list)for(Object value:list)if(value instanceof Map<?,?> a)result.put(String.valueOf(a.get("javaName")),String.valueOf(a.get("javaType")));Object id=entity.get("id");if(id instanceof Map<?,?> idMap)result.remove(String.valueOf(idMap.get("javaName")));return result;}bad("entityType no existe en app-definition: "+entityType);return Map.of();}
    private boolean matches(String type,Object value){return switch(type){case "String"->value instanceof String;case "Integer","Long","Double","Float","BigDecimal"->value instanceof Number||value instanceof String s&&number(s);case "Boolean"->value instanceof Boolean;case "UUID"->value instanceof String s&&uuid(s);case "LocalDate"->value instanceof String s&&date(s);case "LocalDateTime"->value instanceof String s&&dateTime(s);default->value instanceof Map<?,?>||value instanceof String;};}
    private boolean number(String v){try{new BigDecimal(v);return true;}catch(Exception e){return false;}}private boolean uuid(String v){try{UUID.fromString(v);return true;}catch(Exception e){return false;}}private boolean date(String v){try{LocalDate.parse(v);return true;}catch(Exception e){return false;}}private boolean dateTime(String v){try{LocalDateTime.parse(v);return true;}catch(Exception e){return false;}}private void bad(String message){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
