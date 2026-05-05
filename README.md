# Lumina Code Compiler

Lumina Code Compiler is an online multi-language code execution platform built with Spring Boot, featuring a modern glassmorphic UI, an interactive web-based terminal, and snippet sharing capabilities.

## Features

- **Multi-Language Support**: Execute Java, Python, C, and C++ code.
- **Interactive Terminal**: Fully interactive web-based terminal using `xterm.js` and WebSockets for real-time I/O, supporting programs that require user input.
- **Modern IDE Interface**: Integrated Monaco Editor for advanced code editing (syntax highlighting, autocomplete).
- **Beautiful UI**: Dark-themed, glassmorphic design built with Tailwind CSS.
- **Snippet Management**: Save and share code snippets securely, powered by MongoDB Atlas.
- **Authentication**: JWT-based secure user authentication.

## Tech Stack

- **Backend**: Java 21, Spring Boot 3.2.4, Spring Security, Spring WebSockets, JWT.
- **Database**: MongoDB (Spring Data MongoDB).
- **Frontend**: HTML5, Vanilla JS, Tailwind CSS, Monaco Editor, xterm.js.
- **Deployment**: Docker, Render.com.

## Getting Started

### Prerequisites

- Java 21
- Maven
- MongoDB Atlas account (or local MongoDB instance)
- GCC/G++ and Python installed on your system (for local execution of C/C++ and Python)

### Setup

1. **Clone the repository:**
   ```bash
   git clone <repository-url>
   cd <repository-directory>
   ```

2. **Configure Environment Variables:**
   Create an `application.properties` or set environment variables for your MongoDB connection and JWT secret:
   ```properties
   spring.data.mongodb.uri=mongodb+srv://<username>:<password>@<cluster>.mongodb.net/<dbname>?retryWrites=true&w=majority
   jwt.secret=YourSuperSecretKeyForJWTGeneration
   ```

3. **Run the Application:**
   You can run the application using Maven:
   ```bash
   ./mvnw spring-boot:run
   ```
   Or using the provided PowerShell script:
   ```powershell
   .\run_project.ps1
   ```

4. **Access the App:**
   Open your browser and navigate to `http://localhost:8080`.

## Deployment

The project is configured for easy deployment on [Render.com](https://render.com) using Docker.

- `Dockerfile`: Configured to install necessary compilers (gcc, g++, python) and run the Spring Boot application.
- `render.yaml`: Blueprint configuration for deploying the web service on Render.

## License

This project is licensed under the MIT License.
