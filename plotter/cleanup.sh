#!/bin/bash

# Check if a file was passed as an argument
if [ $# -ne 1 ]; then
    echo "Usage: $0 <filename>"
    exit 1
fi

file="$1"

# Check if the file exists
if [ ! -f "$file" ]; then
    echo "Error: File '$file' not found."
    exit 1
fi

# Use sed to remove all occurrences of \mathdefault and update the file
sed -i 's/\\mathdefault//g' "$file"

echo "All occurrences of '\\mathdefault' have been removed from '$file'."
