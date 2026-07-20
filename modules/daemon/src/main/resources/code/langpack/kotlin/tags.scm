; Kotlin definitions and lightweight references.
(package_header (qualified_identifier) @def.name) @def.package

(class_declaration "interface" name: (identifier) @def.name) @def.interface
(class_declaration name: (identifier) @def.name) @def.class
(object_declaration name: (identifier) @def.name) @def.class
(type_alias type: (identifier) @def.name) @def.type_alias

(function_declaration name: (identifier) @def.name) @def.function
(class_body (function_declaration name: (identifier) @def.name) @def.method)
(property_declaration (variable_declaration (identifier) @def.name)) @def.field

(import (qualified_identifier) @ref.name) @ref.import
(call_expression (identifier) @ref.name) @ref.call
