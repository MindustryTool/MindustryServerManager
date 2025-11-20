#!/bin/bash

# --- Mindustry Server Manager Setup Script ---
# This script performs the following actions:
# 1. Installs Docker and the Docker Compose plugin (assuming Debian/Ubuntu).
# 2. Adds the current user to the 'docker' group (requires re-login to take full effect).
# 3. Downloads the required docker-compose.yml file.
# 4. Logs into the GitHub Container Registry (ghcr.io).
# 5. Runs docker compose to start the application.

set -e # Exit immediately if a command exits with a non-zero status.

CURRENT_DIR=$(pwd)
COMPOSE_URL="https://raw.githubusercontent.com/MindustryTool/MindustryServerManager/main/docker-compose.yml"
COMPOSE_FILE="docker-compose.yml"

echo "--- 1. Checking and Installing Docker and Docker Compose Plugin ---"

if ! command -v docker &> /dev/null
then
    echo "Docker not found. Starting installation for Debian/Ubuntu based systems..."
    
    # Update package index and install dependencies
    sudo apt-get update -y
    sudo apt-get install -y ca-certificates curl gnupg lsb-release

    # Add Docker's official GPG key
    sudo install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    sudo chmod a+r /etc/apt/keyrings/docker.gpg

    # Add Docker repository
    echo \
      "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
      $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
      sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

    # Install Docker Engine and Compose Plugin
    sudo apt-get update -y
    sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    
    echo "Docker installation complete."

    # Post-installation step: Add current user to the docker group
    echo "Adding user '$USER' to the 'docker' group to run Docker without sudo."
    sudo usermod -aG docker "$USER"
    
    echo "----------------------------------------------------------------------------------"
    echo "!!! IMPORTANT: For the 'docker' group changes to take effect, you must log out"
    echo "!!! and log back in, or run 'newgrp docker'. Until then, you may need to use 'sudo'."
    echo "----------------------------------------------------------------------------------"
else
    echo "Docker is already installed."
fi


echo "--- 2. Downloading docker-compose.yml from GitHub ---"
echo "Target URL: $COMPOSE_URL"
curl -sSL "$COMPOSE_URL" -o "$COMPOSE_FILE"

if [ -f "$COMPOSE_FILE" ]; then
    echo "Successfully downloaded '$COMPOSE_FILE' to the current directory."
else
    echo "Error: Failed to download the docker-compose file. Exiting."
    exit 1
fi

echo "--- 3. Logging into GitHub Container Registry (ghcr.io) ---"
echo "You will need to enter your GitHub username and a Personal Access Token (PAT) as the password."
echo "The PAT must have the 'read:packages' scope."

# The login command will prompt for username and password interactively
docker login ghcr.io

if [ $? -eq 0 ]; then
    echo "GitHub Registry login successful."
else
    echo "GitHub Registry login failed. The script will continue, but the 'docker compose' step may fail if images are private."
fi


echo "--- 4. Running Docker Compose ---"
echo "Starting containers in detached mode (-d)..."

# Use the modern 'docker compose' command
docker compose -f "$COMPOSE_FILE" up -d

echo "--- Setup Complete! ---"
echo "The Mindustry Server Manager containers are running in the background."
echo "You can check their status with: docker compose ps"
echo "Remember to re-login if you want to run docker commands without 'sudo'."
