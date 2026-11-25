package com.rssolplan.edu.domain.neis;

import java.util.List;

record SchoolSearch(List<SchoolOption> items, int page, int size, int totalCount, boolean hasNext) {}
