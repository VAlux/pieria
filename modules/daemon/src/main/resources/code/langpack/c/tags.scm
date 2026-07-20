; C definitions and relations.
(struct_specifier name: (type_identifier) @def.name body: (_)) @def.class
(union_specifier name: (type_identifier) @def.name body: (_)) @def.class
(enum_specifier name: (type_identifier) @def.name body: (_)) @def.enum
(type_definition declarator: (type_identifier) @def.name) @def.type_alias
(function_definition declarator: (function_declarator declarator: (identifier) @def.name)) @def.function

(preproc_include path: (_) @ref.name) @ref.import
(call_expression function: (identifier) @ref.name) @ref.call
(call_expression function: (field_expression field: (field_identifier) @ref.name)) @ref.call
