package com.hmdp.dto;

import lombok.Data;

@Data
public class ShopTypeDTO {
    //  [{"id":1,"name":"美食","icon":"/types/ms.png","sort":1},...]
    private Long id;
    private String name;
    private String icon;
    private Integer sort;

    @Override
    public String toString() {
        return "{" +
                "id:" + id +
                ", name:'" + name + '\'' +
                ", icon:'" + icon + '\'' +
                ", sort:" + sort +
                '}';
    }
}

