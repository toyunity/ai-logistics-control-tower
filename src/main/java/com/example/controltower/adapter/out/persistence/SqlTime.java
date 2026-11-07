package com.example.controltower.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

final class SqlTime {

    private SqlTime() {}

    /** 컬럼은 마이크로초까지다. 드라이버의 반올림에 맡기지 않고 내려서, 만료 시각이 원래보다 뒤로 밀리지 않게 한다. */
    static OffsetDateTime bind(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    static Instant read(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
