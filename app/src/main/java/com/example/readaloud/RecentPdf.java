package com.example.readaloud;

public class RecentPdf {
    public String title;
    public String uriString;
    public int lastPage;
    public int totalPages;
    public int lastWordIndex;

    public RecentPdf(String title, String uriString, int lastPage, int totalPages, int lastWordIndex) {
        this.title = title;
        this.uriString = uriString;
        this.lastPage = lastPage;
        this.totalPages = totalPages;
        this.lastWordIndex = lastWordIndex;
    }
}