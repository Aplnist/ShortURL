package com.aplnist.dwz.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author Aplnist
 * @date 2023-07-16
 */
@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class GenerateCmd {
    private String longURL;
}
