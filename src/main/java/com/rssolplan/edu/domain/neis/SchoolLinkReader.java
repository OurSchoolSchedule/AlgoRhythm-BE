package com.rssolplan.edu.domain.neis;

/** Read on every request, before accessing cached or remote data. userId is the authenticated principal. */
public interface SchoolLinkReader {
    SchoolLink read(long userId, long schoolId);
}
