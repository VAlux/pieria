; Swift definitions and relations.
(class_declaration "class" name: (type_identifier) @def.name) @def.class
(class_declaration "actor" name: (type_identifier) @def.name) @def.class
(class_declaration "struct" name: (type_identifier) @def.name) @def.class
(class_declaration "enum" name: (type_identifier) @def.name) @def.enum
(protocol_declaration name: (type_identifier) @def.name) @def.interface
(typealias_declaration name: (type_identifier) @def.name) @def.type_alias
(function_declaration name: (simple_identifier) @def.name) @def.function
(class_body (function_declaration name: (simple_identifier) @def.name) @def.method)
(protocol_body (protocol_function_declaration name: (simple_identifier) @def.name) @def.method)
(property_declaration name: (pattern (simple_identifier) @def.name)) @def.field

(import_declaration (identifier) @ref.name) @ref.import
(call_expression (simple_identifier) @ref.name) @ref.call
