# Note Images

V1.18 adds image insertion to Note mode.

- The Note toolbar opens the Android system photo picker.
- Multiple images can be selected in one operation.
- Selected images are copied into the app-private `files/note_images/` directory.
- The note content stores internal image references rather than Base64-encoding large files into localStorage.
- Images can be previewed and removed from the note.
- Deleting a note also cleans up its private image attachments.

This design keeps existing text-only notes compatible and avoids requesting broad access to the user's entire photo library.
