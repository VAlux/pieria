; C++ definitions and relations.
(namespace_definition name: (_) @def.name) @def.package
(class_specifier name: (_) @def.name) @def.class
(struct_specifier name: (type_identifier) @def.name body: (_)) @def.class
(union_specifier name: (type_identifier) @def.name body: (_)) @def.class
(enum_specifier name: (type_identifier) @def.name body: (_)) @def.enum
(type_definition declarator: (type_identifier) @def.name) @def.type_alias
(alias_declaration name: (type_identifier) @def.name) @def.type_alias
(function_definition declarator: (function_declarator declarator: (identifier) @def.name)) @def.function
(function_definition declarator: (function_declarator declarator: (field_identifier) @def.name)) @def.method
(function_definition declarator: (function_declarator declarator: (qualified_identifier name: (_) @def.name))) @def.method

(preproc_include path: (_) @ref.name) @ref.import
(class_specifier (base_class_clause (_) @ref.name)) @ref.extends
(call_expression function: (identifier) @ref.name) @ref.call
(call_expression function: (field_expression field: (field_identifier) @ref.name)) @ref.call
