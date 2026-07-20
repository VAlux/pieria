; Go definitions and relations.
(package_clause (package_identifier) @def.name) @def.package
(type_spec name: (type_identifier) @def.name type: (struct_type)) @def.class
(type_spec name: (type_identifier) @def.name type: (interface_type)) @def.interface
(type_spec name: (type_identifier) @def.name) @def.type_alias
(function_declaration name: (identifier) @def.name) @def.function
(method_declaration name: (field_identifier) @def.name) @def.method
(var_spec name: (identifier) @def.name) @def.variable
(const_spec name: (identifier) @def.name) @def.variable

(import_spec path: (interpreted_string_literal) @ref.name) @ref.import
(import_spec path: (raw_string_literal) @ref.name) @ref.import
(call_expression function: (identifier) @ref.name) @ref.call
(call_expression function: (selector_expression field: (field_identifier) @ref.name)) @ref.call
