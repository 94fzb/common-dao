package com.hibegin.common.dao.dto;

import java.util.List;

public interface PageRequest {

    Long getSize();

    Long getPage();

    List<OrderBy> getSorts();

    default Long getOffset() {
        return (getPage() - 1) * getSize();
    }
}
