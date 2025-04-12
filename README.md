# Video-e-Audio

A Spring Boot application to download videos and audio from YouTube using yt-dlp.

## Prerequisites
- Java 17
- Maven
- PostgreSQL
- yt-dlp (configure path in `application.properties`)
- ffmpeg (configure path in `application.properties`)

## Setup
1. Clone the repository:
2. Configure `application.properties` with your PostgreSQL credentials and paths to yt-dlp/ffmpeg.
3. Run the app:
4. Open `http://localhost:8080` in your browser.

## Usage
- Enter a YouTube URL, choose MP4 or MP3, and click "Download".
- View download history at `/downloads`.
